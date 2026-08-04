package com.ruoyi.framework.web.service.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.config.properties.AiRobotProperties;
import com.ruoyi.framework.web.service.IAiChatService;
import com.ruoyi.system.domain.AiConversation;
import com.ruoyi.system.domain.AiMessage;
import com.ruoyi.system.service.IAiConversationService;
import com.ruoyi.system.service.IAiMessageService;

@Service
public class AiChatServiceImpl implements IAiChatService
{
    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    @Autowired
    private AiRobotProperties aiRobotProperties;

    @Autowired
    private IAiConversationService conversationService;

    @Autowired
    private IAiMessageService messageService;

    @Override
    public SseEmitter chatStream(AiMessage userMessage, List<AiMessage> historyMessages, Long aiMessageId, Map<String, Object> ragConfig)
    {
        SseEmitter emitter = new SseEmitter(0L);
        StringBuilder fullContent = new StringBuilder();

        emitter.onCompletion(() -> {
            if (aiMessageId != null && fullContent.length() > 0)
            {
                try
                {
                    AiMessage updateMsg = new AiMessage();
                    updateMsg.setMessageId(aiMessageId);
                    updateMsg.setContent(fullContent.toString());
                    messageService.updateMessage(updateMsg);

                    conversationService.updateLastMessage(userMessage.getConversationId(),
                        fullContent.length() > 50 ? fullContent.substring(0, 50) : fullContent.toString());
                }
                catch (Exception e)
                {
                    log.error("更新AI消息失败", e);
                }
            }
        });

        new Thread(() -> {
            try
            {
                String conversationType = resolveConversationType(userMessage.getConversationId());

                if ("5".equals(conversationType))
                {
                    String ragAnswer;
                    if (isRagMockMode())
                    {
                        ragAnswer = getMockContent(userMessage);
                    }
                    else
                    {
                        ragAnswer = callRagApi(userMessage, ragConfig);
                    }
                    fullContent.append(ragAnswer);
                    emitter.send(SseEmitter.event().data(ragAnswer));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                    return;
                }

                if (isMockMode())
                {
                    streamMockResponse(userMessage, emitter, fullContent);
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                    return;
                }

                streamRealApiResponse(userMessage, historyMessages, emitter, fullContent);
            }
            catch (Exception e)
            {
                log.error("AI流式响应异常", e);
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    @Override
    public String chat(AiMessage userMessage, List<AiMessage> historyMessages, Map<String, Object> ragConfig)
    {
        String conversationType = resolveConversationType(userMessage.getConversationId());

        if ("5".equals(conversationType))
        {
            if (isRagMockMode())
            {
                return getMockContent(userMessage);
            }
            try
            {
                return callRagApi(userMessage, ragConfig);
            }
            catch (Exception e)
            {
                log.error("RAG API响应异常", e);
                return "抱歉，RAG知识库服务暂时不可用，请稍后再试。";
            }
        }

        if (isMockMode())
        {
            return getMockContent(userMessage);
        }

        try
        {
            return callRealApi(userMessage, historyMessages, false);
        }
        catch (Exception e)
        {
            log.error("AI响应异常", e);
            return "抱歉，AI服务暂时不可用，请稍后再试。";
        }
    }

    private boolean isMockMode()
    {
        String apiKey = aiRobotProperties.getApiKey();
        String apiUrl = aiRobotProperties.getApiUrl();
        return StringUtils.isEmpty(apiKey)
            || "your-api-key-here".equals(apiKey)
            || "your-deepseek-api-key-here".equals(apiKey)
            || StringUtils.isEmpty(apiUrl)
            || "https://api.example.com/v1/chat/completions".equals(apiUrl);
    }

    private boolean isRagMockMode()
    {
        String ragApiUrl = aiRobotProperties.getRagApiUrl();
        return StringUtils.isEmpty(ragApiUrl)
            || "http://localhost:8000/ask".equals(ragApiUrl);
    }

    private void streamRagApiResponse(AiMessage userMessage, Map<String, Object> ragConfig,
        SseEmitter emitter, StringBuilder fullContent) throws Exception
    {
        String answer = callRagApi(userMessage, ragConfig);
        for (int i = 0; i < answer.length(); i++)
        {
            String token = String.valueOf(answer.charAt(i));
            fullContent.append(token);
            emitter.send(SseEmitter.event().data(token));
            Thread.sleep(30);
        }
    }

    private String callRagApi(AiMessage userMessage, Map<String, Object> ragConfig) throws Exception
    {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", userMessage.getContent());

        if (ragConfig != null)
        {
            if (ragConfig.get("enableRerank") != null)
            {
                requestBody.put("use_rerank", ragConfig.get("enableRerank"));
            }
            else
            {
                requestBody.put("use_rerank", true);
            }
            if (ragConfig.get("enableHyde") != null)
            {
                requestBody.put("use_hyde", ragConfig.get("enableHyde"));
            }
            else
            {
                requestBody.put("use_hyde", false);
            }
            if (ragConfig.get("tenantId") != null)
            {
                requestBody.put("tenant_id", ragConfig.get("tenantId"));
            }
            else
            {
                requestBody.put("tenant_id", "default");
            }
            if (ragConfig.get("fusionStrategy") != null)
            {
                requestBody.put("fusion_strategy", ragConfig.get("fusionStrategy"));
            }
            else
            {
                requestBody.put("fusion_strategy", "rrf");
            }
        }
        else
        {
            requestBody.put("use_rerank", true);
            requestBody.put("use_hyde", false);
            requestBody.put("tenant_id", "default");
            requestBody.put("fusion_strategy", "rrf");
        }

        URL url = new URL(aiRobotProperties.getRagApiUrl());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(aiRobotProperties.getConnectTimeout() * 1000);
        conn.setReadTimeout(aiRobotProperties.getReadTimeout() * 1000);

        try (OutputStream os = conn.getOutputStream())
        {
            byte[] input = JSON.toJSONString(requestBody).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK)
        {
            String errorMsg = readErrorResponse(conn);
            throw new RuntimeException("RAG API调用失败，状态码：" + responseCode + "，响应：" + errorMsg);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
        }

        return parseRagResponse(response.toString());
    }

    private String parseRagResponse(String response)
    {
        try
        {
            Map<String, Object> json = JSON.parseObject(response);
            StringBuilder result = new StringBuilder();

            if (json.get("answer") != null)
            {
                result.append(json.get("answer").toString());
            }

            if (json.get("sources") != null)
            {
                List<String> sources = (List<String>) json.get("sources");
                if (!sources.isEmpty())
                {
                    result.append("\n\n---\n\n📚 **引用来源**：\n");
                    for (int i = 0; i < sources.size(); i++)
                    {
                        result.append((i + 1) + ". " + sources.get(i) + "\n");
                    }
                }
            }

            return result.toString();
        }
        catch (Exception e)
        {
            log.error("解析RAG响应失败: {}", response, e);
            return response;
        }
    }

    private void streamRealApiResponse(AiMessage userMessage, List<AiMessage> historyMessages,
        SseEmitter emitter, StringBuilder fullContent) throws Exception
    {
        String requestBody = buildRequestBody(userMessage, historyMessages, true);

        URL url = new URL(aiRobotProperties.getApiUrl());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + aiRobotProperties.getApiKey());
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(aiRobotProperties.getConnectTimeout() * 1000);
        conn.setReadTimeout(aiRobotProperties.getReadTimeout() * 1000);

        try (OutputStream os = conn.getOutputStream())
        {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK)
        {
            String errorMsg = readErrorResponse(conn);
            log.error("AI API调用失败，状态码：{}，响应：{}", responseCode, errorMsg);
            emitter.send(SseEmitter.event().data("AI服务调用失败：" + errorMsg));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
            return;
        }

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;

            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("data: "))
                {
                    String data = line.substring(6);
                    if ("[DONE]".equals(data))
                    {
                        break;
                    }

                    String content = parseContentFromSse(data);
                    if (content != null && !content.isEmpty())
                    {
                        fullContent.append(content);
                        emitter.send(SseEmitter.event().data(content));
                    }
                }
            }

            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        }
    }

    private String callRealApi(AiMessage userMessage, List<AiMessage> historyMessages, boolean stream)
        throws Exception
    {
        String requestBody = buildRequestBody(userMessage, historyMessages, stream);

        URL url = new URL(aiRobotProperties.getApiUrl());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + aiRobotProperties.getApiKey());
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(aiRobotProperties.getConnectTimeout() * 1000);
        conn.setReadTimeout(aiRobotProperties.getReadTimeout() * 1000);

        try (OutputStream os = conn.getOutputStream())
        {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK)
        {
            String errorMsg = readErrorResponse(conn);
            throw new RuntimeException("AI API调用失败，状态码：" + responseCode + "，响应：" + errorMsg);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
        }

        return parseContentFromResponse(response.toString());
    }

    private String buildRequestBody(AiMessage userMessage, List<AiMessage> historyMessages, boolean stream)
    {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", aiRobotProperties.getModel());
        requestBody.put("stream", stream);
        requestBody.put("max_tokens", aiRobotProperties.getMaxTokens());
        requestBody.put("temperature", aiRobotProperties.getTemperature());

        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(buildSystemMessage(userMessage));

        if (historyMessages != null && !historyMessages.isEmpty())
        {
            for (AiMessage msg : historyMessages)
            {
                if (msg.getContent() == null || msg.getContent().isEmpty())
                {
                    continue;
                }
                Map<String, String> historyMsg = new HashMap<>();
                historyMsg.put("role", msg.getRole());
                historyMsg.put("content", msg.getContent());
                messages.add(historyMsg);
            }
        }

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage.getContent());
        messages.add(userMsg);

        requestBody.put("messages", messages);

        return JSON.toJSONString(requestBody);
    }

    private Map<String, String> buildSystemMessage(AiMessage userMessage)
    {
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");

        String conversationType = "1";
        if (userMessage.getConversationId() != null)
        {
            conversationType = resolveConversationType(userMessage.getConversationId());
        }

        String systemPrompt = switch (conversationType)
        {
            case "2" -> "你是一个专业的翻译助手。请将用户输入的内容翻译成英文和日文。先给出英文翻译，再给出日文翻译。保持原文的语气和风格。";
            case "3" -> "你是一个会议纪要助手。请根据用户提供的会议内容或主题，生成一份结构化的会议纪要。包含：会议主题、参会人员、会议时间、讨论内容、决议事项、待办事项。";
            case "4" -> "你是一个专业的写作助手。请根据用户的需求，帮助撰写、润色或优化文章内容。提供高质量、结构清晰的文字。";
            case "5" -> "你是一个RAG知识库问答助手。请基于检索到的知识片段回答用户的问题。回答时要引用来源，确保信息准确。如果知识库中没有相关信息，请诚实告知。";
            default -> "你是一个友好、专业的AI助手。请用简洁、准确的语言回答用户的问题。如果不确定答案，请诚实告知。";
        };

        systemMsg.put("content", systemPrompt);
        return systemMsg;
    }

    private String resolveConversationType(Long conversationId)
    {
        try
        {
            if (conversationService != null)
            {
                AiConversation conv = conversationService.selectConversationById(conversationId);
                if (conv != null && conv.getConversationType() != null)
                {
                    return conv.getConversationType();
                }
            }
        }
        catch (Exception e)
        {
            log.warn("获取会话类型失败，使用默认类型", e);
        }
        return "1";
    }

    private String parseContentFromSse(String data)
    {
        try
        {
            Map<String, Object> json = JSON.parseObject(data);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");
            if (choices != null && !choices.isEmpty())
            {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                if (delta != null && delta.get("content") != null)
                {
                    return (String) delta.get("content");
                }
            }
        }
        catch (Exception e)
        {
            log.debug("解析SSE数据失败: {}", data);
        }
        return null;
    }

    private String parseContentFromResponse(String response)
    {
        try
        {
            Map<String, Object> json = JSON.parseObject(response);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");
            if (choices != null && !choices.isEmpty())
            {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message != null && message.get("content") != null)
                {
                    return (String) message.get("content");
                }
            }
        }
        catch (Exception e)
        {
            log.error("解析响应失败: {}", response, e);
        }
        return "";
    }

    private String readErrorResponse(HttpURLConnection conn)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8)))
        {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
            return response.toString();
        }
        catch (Exception e)
        {
            return "无法读取错误响应";
        }
    }

    private void streamMockResponse(AiMessage userMessage, SseEmitter emitter, StringBuilder fullContent)
        throws Exception
    {
        String mockContent = getMockContent(userMessage);
        for (int i = 0; i < mockContent.length(); i++)
        {
            String token = String.valueOf(mockContent.charAt(i));
            fullContent.append(token);
            emitter.send(SseEmitter.event().data(token));
            Thread.sleep(30);
        }
    }

    private String getMockContent(AiMessage userMessage)
    {
        String response = "";
        String userContent = userMessage.getContent();
        String conversationType = resolveConversationType(userMessage.getConversationId());

        switch (conversationType)
        {
            case "1":
                response = String.format(
                    "你好！我是智聊助手 🤖\n\n" +
                    "关于你提到的「%s」，我来详细聊聊：\n\n" +
                    "📌 这是一个非常好的问题！让我从几个方面来分析：\n\n" +
                    "1️⃣ **背景介绍**：这个话题涉及到多个方面的知识，需要综合考虑。\n\n" +
                    "2️⃣ **核心要点**：关键在于理解本质问题，而非表面现象。\n\n" +
                    "3️⃣ **实践建议**：建议从简单场景开始，逐步深入。\n\n" +
                    "💡 **总结**：希望以上分析对你有帮助！如果还有其他问题，随时问我～",
                    userContent);
                break;
            case "2":
                response = String.format(
                    "🌐 翻译结果如下：\n\n" +
                    "【原文】\n%s\n\n" +
                    "【英文翻译】\nThis is the English translation of your text. The translation maintains the original meaning and tone while ensuring natural fluency in the target language.\n\n" +
                    "【日文翻译】\nこれはあなたのテキストの日本語訳です。元の意味とニュアンスを保ちつつ、自然な流れになるように翻訳しています。\n\n" +
                    "📝 备注：以上翻译为演示数据，对接真实API后将提供准确翻译。",
                    userContent);
                break;
            case "3":
                response = String.format(
                    "📝 会议纪要生成中...\n\n" +
                    "═══════════════════════════════\n" +
                    "              会 议 纪 要\n" +
                    "═══════════════════════════════\n\n" +
                    "📅 会议主题：%s\n\n" +
                    "👥 参会人员：产品经理、开发负责人、测试负责人\n\n" +
                    "🕐 会议时间：2026-07-28 14:00-15:30\n\n" +
                    "【讨论内容】\n" +
                    "1. 项目进度回顾 — 整体进度符合预期\n" +
                    "2. 技术方案确认 — 采用微服务架构\n" +
                    "3. 风险点梳理 — 识别3个关键风险\n\n" +
                    "【决议事项】\n" +
                    "✅ 事项一：下周三前完成技术评审\n" +
                    "✅ 事项二：测试团队提前介入\n" +
                    "✅ 事项三：每日站会同步进度\n\n" +
                    "【待办事项】\n" +
                    "⏳ 张三 — 完成接口文档\n" +
                    "⏳ 李四 — 准备测试用例\n" +
                    "⏳ 王五 — 跟进依赖方\n\n" +
                    "═══════════════════════════════",
                    userContent);
                break;
            case "4":
                response = String.format(
                    "✍️ 辅助写作 — 为您生成内容\n\n" +
                    "基于您的需求「%s」，我为您撰写了以下内容：\n\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "在当今快速发展的时代，这个话题变得越来越重要。\n\n" +
                    "首先，我们需要认识到问题的核心所在。只有深入理解本质，才能找到真正有效的解决方案。无数实践证明，那些能够取得突破的人，往往是能够从多角度思考问题的人。\n\n" +
                    "其次，行动是关键。理论固然重要，但没有实践的支撑，一切都只是空谈。建议您从以下几个方面入手：\n\n" +
                    "• 制定清晰的目标和计划\n" +
                    "• 分解任务，逐步推进\n" +
                    "• 定期复盘，持续优化\n\n" +
                    "最后，请记住：每一个伟大的成就，都始于一个勇敢的开始。只要坚持下去，就一定能够看到成果。\n\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "💡 如需调整风格（正式/轻松/专业/文艺）或继续扩写，请告诉我！",
                    userContent);
                break;
            case "5":
                response = String.format(
                    "📚 **RAG知识库问答结果**\n\n" +
                    "🔍 **检索配置**：BM25 + 向量语义混合检索，加权融合策略\n\n" +
                    "---\n\n" +
                    "📖 **问题**：%s\n\n" +
                    "📄 **召回知识片段（Top-3）**：\n\n" +
                    "**【片段1】**（来源：知识库文档 A-001，相似度：0.92）\n" +
                    "> 这是关于该问题的第一个相关知识片段。它包含了问题的核心背景和基本概念，为理解问题提供了重要的上下文信息。文档中详细阐述了相关的原理和实践方法。\n\n" +
                    "**【片段2】**（来源：知识库文档 B-023，相似度：0.87）\n" +
                    "> 第二个相关片段从不同角度补充了问题的解决方案。它包含了具体的实施步骤和注意事项，可以帮助用户更全面地理解和处理相关问题。\n\n" +
                    "**【片段3】**（来源：知识库文档 C-107，相似度：0.78）\n" +
                    "> 第三个片段提供了实际案例和最佳实践的参考。通过分析成功案例，可以更好地把握问题的关键点，并避免常见的陷阱和错误。\n\n" +
                    "---\n\n" +
                    "💡 **综合回答**：\n\n" +
                    "根据知识库检索结果，关于「%s」的解答如下：\n\n" +
                    "这个问题涉及多个方面的内容。首先，从核心概念来看【片段1】，我们需要理解问题的本质和背景。其次，在具体实施方面【片段2】，建议按照规范的步骤进行操作，并注意相关的细节事项。最后，通过参考实际案例【片段3】，可以帮助我们更好地应用这些知识到实际场景中。\n\n" +
                    "📌 **建议**：\n" +
                    "1. 先通读相关文档，建立整体认知\n" +
                    "2. 按照实施步骤逐步推进\n" +
                    "3. 参考成功案例，避免常见问题\n" +
                    "4. 如有疑问，可进一步咨询专业人员\n\n" +
                    "---\n" +
                    "🔖 **引用来源**：A-001、B-023、C-107\n\n" +
                    "💬 如需查看更多相关文档或深入了解某个方面，请告诉我！",
                    userContent, userContent);
                break;
            default:
                response = "你好！我是AI助手，有什么可以帮您？";
                break;
        }
        return response;
    }
}
