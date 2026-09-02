<!--
  ====================================================================================
  组件名称：AiWidget.vue（小窗口 AI 助手组件）
  ====================================================================================
  功能概述：
    本组件是「数智员工」系统的悬浮小窗口入口，固定显示在页面右下角。
    用户点击悬浮按钮后展开一个 430×600 的小窗口，提供 AI 智能对话能力，
    无需跳转独立页面即可在当前页面侧边进行快速问答交互。

  主要功能模块：
    1. 悬浮入口按钮（FAB）：带脉冲动画的圆形按钮，点击展开小窗口
    2. 主选项卡「小信智聊」：核心对话功能，蓝色主题样式
    3. 子选项卡行：翻译、会议纪要、辅助写作三个子功能
    4. 欢迎区：无对话时显示模板按钮 + 示例问题列表
    5. 对话区：用户消息（蓝色气泡）+ AI 回复（Markdown 渲染）
    6. 文件上传：仅「会议纪要」选项卡支持，可上传 .txt 和音频文件
    7. 输入区：自适应高度文本框 + 内嵌发送按钮，回车发送
    8. 操作按钮：播放视频、展开大页面、关闭小窗口

  数据流：
    - 前端固定 Tab 配置 + onMounted 时请求后端覆盖示例/模板
    - sendMessage 优先尝试 SSE 流式请求，3 秒未响应自动回退模拟流式输出
    - 模拟回复按选项卡类型生成不同内容（chat/translate/meeting/writing）

  依赖：
    - Vue3 Composition API（ref、computed、onMounted）
    - Element Plus（el-input、el-button、el-icon、el-tooltip、ElMessage）
    - @element-plus/icons-vue 图标库
    - @/api/ai/chat 后端 API（getTabConfigs、buildStreamUrl、uploadFile）
  ====================================================================================
-->
<template>
  <div class="ai-widget">
    <!-- 悬浮入口按钮（FAB）：圆形渐变按钮 + 脉冲光圈动画，点击展开小窗口 -->
    <div v-if="!showMini" class="ai-widget__fab" @click="openMini">
      <div class="ai-widget__fab-icon">🤖</div>
      <div class="ai-widget__fab-pulse"></div>
    </div>

    <!-- 小窗口模式（showMini 为 true 时显示整个对话窗口） -->
    <div v-if="showMini" class="ai-widget__mini" :class="{ 'is-playing-video': showVideoPlayer }">

      <!-- ============ 顶部头部区域（header） ============ -->
      <!-- 左侧：主选项卡「小信智聊」（点击切换至 chat 类型，高亮显示） -->
      <!-- 右侧：三个操作按钮（播放视频 / 展开大页面 / 关闭窗口） -->
      <div class="ai-widget__header">
        <!-- 主选项卡：小信智聊，点击切换为 chat 选项卡 -->
        <div
          class="ai-widget__tab-primary"
          :class="{ 'is-active': activeTab === 'chat' }"
          @click="switchTab('chat')"
        >
          <span class="ai-widget__tab-primary-icon">💬</span>
          <span class="ai-widget__tab-primary-name">小信智聊</span>
        </div>
        <!-- 操作按钮组：播放视频、展开大页面、关闭 -->
        <div class="ai-widget__actions">
          <!-- 播放视频按钮：点击展开视频播放覆盖层 -->
          <el-tooltip content="播放视频" placement="bottom" :show-after="900">
            <el-icon class="ai-widget__action-btn" @click="handlePlayVideo" :size="20"><VideoPlay /></el-icon>
          </el-tooltip>
          <!-- 展开大页面按钮：新窗口打开 /ai/chat 路由 -->
          <el-tooltip content="展开详情" placement="bottom" :show-after="300">
            <el-icon class="ai-widget__action-btn" @click="openFullPage" :size="20"><FullScreen /></el-icon>
          </el-tooltip>
          <!-- 关闭按钮：隐藏小窗口回到 FAB 入口 -->
          <el-tooltip content="关闭" placement="bottom" :show-after="300">
            <el-icon class="ai-widget__action-btn is-close" @click="closeMini" :size="20"><Close /></el-icon>
          </el-tooltip>
        </div>
      </div>

      <!-- ============ 子选项卡行（subTabs） ============ -->
      <!-- 包含三个并列子选项卡：翻译 / 会议纪要 / 辅助写作 -->
      <!-- 每个选项卡图标颜色不同（翻译蓝、会议橙、写作紫） -->
      <!-- 点击切换 activeTab，并清空当前对话内容 -->
      <div class="ai-widget__tab-bar">
        <div
          v-for="tab in subTabs"
          :key="tab.key"
          class="ai-widget__tab"
          :class="{ 'is-active': activeTab === tab.key }"
          @click="switchTab(tab.key)"
        >
          <span class="ai-widget__tab-icon" :style="{ color: tabIconColors[tab.key] }">{{ tab.icon }}</span>
          <span class="ai-widget__tab-name">{{ tab.name }}</span>
        </div>
      </div>

      <!-- ============ 内容主体区（body） ============ -->
      <!-- 根据当前是否有对话内容，条件渲染欢迎区或对话区 -->
      <div class="ai-widget__body" ref="chatBodyRef">

        <!-- ---------- 欢迎区（无对话且非加载状态时显示） ---------- -->
        <!-- 包含：模板按钮组（如有）+ 示例问题列表 -->
        <!-- 点击模板切换示例集；点击示例直接发送 -->
        <div v-if="!currentAnswer && !loading" class="ai-widget__welcome">
          <!-- 模板按钮组：当选项卡存在 templates 配置时显示，用于切换不同示例集 -->
          <div v-if="currentTemplates.length" class="ai-widget__templates">
            <div
              v-for="tpl in currentTemplates"
              :key="tpl.key"
              class="ai-widget__template"
              :class="{ 'is-active': activeTemplateKey === tpl.key }"
              @click="selectTemplate(tpl.key)"
            >
              {{ tpl.name }}
            </div>
          </div>
          <!-- 示例问题列表：用户点击直接发送该问题 -->
          <div class="ai-widget__examples">
            <div
              v-for="(example, idx) in currentTabExamples"
              :key="idx"
              class="ai-widget__example"
              @click="sendExample(example)"
            >
              <span class="ai-widget__example-icon">{{ getExampleIcon(idx) }}</span>
              <span class="ai-widget__example-text">{{ example }}</span>
            </div>
          </div>
        </div>

        <!-- ---------- 对话区（已有对话内容或正在加载时显示） ---------- -->
        <!-- 用户消息靠右（蓝色气泡），AI 回复靠左（灰色气泡） -->
        <!-- AI 加载中显示三个跳动小圆点动画 -->
        <div v-else class="ai-widget__chat">
          <!-- 用户消息气泡：右对齐，蓝色背景 -->
          <div v-if="lastUserQuestion" class="ai-widget__user-msg">
            <span class="ai-widget__user-icon">😊</span>
            <span class="ai-widget__user-text">{{ lastUserQuestion }}</span>
          </div>
          <!-- AI 回复气泡：左对齐，Markdown 渲染输出 -->
          <div class="ai-widget__assistant-msg">
            <span class="ai-widget__ai-icon">🤖</span>
            <div class="ai-widget__ai-content">
              <!-- 加载中且无内容：显示三点跳动动画 -->
              <div v-if="loading && !currentAnswer" class="ai-widget__typing">
                <span></span><span></span><span></span>
              </div>
              <!-- 已有内容：渲染 Markdown（renderedAnswer 计算属性） -->
              <div v-else class="ai-widget__markdown" v-html="renderedAnswer"></div>
            </div>
          </div>
        </div>
      </div>

      <!-- ============ 已上传文件卡片列表 ============ -->
      <!-- 当 uploadedFiles 不为空时显示，仅「会议纪要」选项卡可上传 -->
      <!-- 每个卡片显示：文件类型图标 / 文件名 / 元信息 / 删除按钮 -->
      <div v-if="uploadedFiles.length > 0" class="ai-widget__file-list">
        <div
          v-for="file in uploadedFiles"
          :key="file.id"
          class="ai-widget__file-card"
        >
          <!-- 文件类型图标：音频显示🎵，文本显示📄 -->
          <span class="ai-widget__file-type">{{ file.type === 'audio' ? '🎵' : '📄' }}</span>
          <div class="ai-widget__file-info">
            <!-- 文件名（超长省略号显示，title 悬停显示完整名） -->
            <div class="ai-widget__file-name" :title="file.fileName">{{ file.fileName }}</div>
            <!-- 元信息：文件类型 + 文件大小 -->
            <div class="ai-widget__file-meta">{{ file.type === 'audio' ? '音频文件' : '文本文件' }} · {{ formatFileSize(file.size) }}</div>
          </div>
          <!-- 删除按钮：从 uploadedFiles 数组中移除 -->
          <el-icon class="ai-widget__file-remove" @click="removeUploadedFile(file.id)"><Remove /></el-icon>
        </div>
      </div>

      <!-- ============ 输入区（input） ============ -->
      <!-- 布局：[+上传按钮] | [文本框 + 内嵌发送按钮] -->
      <!-- - 上传按钮仅「会议纪要」选项卡显示 -->
      <!-- - 文本框自适应高度（2-4 行） -->
      <!-- - Enter 发送，Shift+Enter 换行 -->
      <div class="ai-widget__input">
        <!-- 隐藏的 file input：通过 triggerFileInput() 触发 click -->
        <input
          ref="fileInputRef"
          type="file"
          accept=".txt,.mp3,.wav,.m4a,.aac,.ogg,.flac"
          class="ai-widget__file-input"
          @change="handleFileUpload"
        />

        <!-- ---------- 上传按钮（仅「会议纪要」选项卡显示）---------- -->
        <!-- 上传中显示 Loading 旋转图标，禁用状态不可点击 -->
        <el-tooltip
          v-if="activeTab === 'meeting'"
          :content="uploading ? '转换中...' : '上传文件'"
          placement="top"
          :show-after="200"
        >
          <div
            class="ai-widget__upload"
            :class="{ 'is-disabled': uploading || loading }"
            @click="triggerFileInput"
          >
            <!-- 非上传中显示 + 号图标 -->
            <el-icon v-if="!uploading"><Plus /></el-icon>
            <!-- 上传中显示 Loading 旋转图标 -->
            <el-icon v-else class="is-loading"><Loading /></el-icon>
          </div>
        </el-tooltip>

        <!-- ---------- 文本输入框 + 内嵌发送按钮 ---------- -->
        <!-- 自适应高度 2-4 行，禁用 loading 期间输入 -->
        <!-- 发送按钮：内容/文件为空或加载中时禁用 -->
        <div class="ai-widget__input-box">
          <el-input
            v-model="inputText"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            :placeholder="inputPlaceholder"
            :disabled="loading"
            @keydown.enter.exact.prevent="handleSend"
            resize="none"
            class="ai-widget__textarea"
          />
          <!-- 内嵌发送按钮：圆角蓝色按钮，含图标 + "发送"文字 -->
          <el-button
            class="ai-widget__send"
            :disabled="(!inputText.trim() && uploadedFiles.length === 0) || loading"
            @click="handleSend"
          >
            <el-icon><Promotion /></el-icon>
            <span>发送</span>
          </el-button>
        </div>
      </div>

      <!-- 底部提示文字：操作快捷键说明 -->
      <div class="ai-widget__footer">Enter 发送 · Shift + Enter 换行</div>

      <!-- ============ 视频播放覆盖层 ============ -->
      <!-- 点击右上角播放按钮后展开，覆盖整个小窗口内容区 -->
      <!-- 视频播放完毕后自动关闭回到示例问题页；也可手动点击右上角关闭 -->
      <div v-if="showVideoPlayer" class="ai-widget__video-overlay">
        <video
          ref="videoRef"
          class="ai-widget__video"
          src="/videos/中信数字人走出视频.mp4"
          autoplay
          @ended="handleVideoEnd"
        ></video>
        <!-- 右上角手动关闭按钮 -->
        <el-icon class="ai-widget__video-close" @click="handleCloseVideo" :size="18"><Close /></el-icon>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * ====================================================================================
 * Script 逻辑层（Composition API + TypeScript）
 * ====================================================================================
 * 主要分为以下几个部分：
 *   1. 依赖导入：Vue3 / Element Plus 图标 / ElMessage / 后端 API
 *   2. 静态配置：示例图标集、固定 Tab 列表、图标颜色映射
 *   3. 响应式状态：showMini / loading / activeTab / inputText / currentAnswer 等
 *   4. 计算属性：当前 Tab、模板、示例集、placeholder、Markdown 渲染结果
 *   5. 工具方法：renderMarkdown（简易 Markdown → HTML 转换）
 *   6. 业务方法：switchTab / selectTemplate / openMini / closeMini / openFullPage
 *                handleSend / sendExample / triggerFileInput / handleFileUpload
 *                removeUploadedFile / formatFileSize / getMockReplySmall / sendMessage
 *   7. 生命周期：onMounted 初始化加载 Tab 配置
 * ====================================================================================
 */
import { ref, computed, onMounted, watch, nextTick } from 'vue'
// Element Plus 图标：全屏、发送、关闭、加载中、移除、加号、视频播放
import {
  FullScreen, Promotion, Close, Loading, Remove, Plus, VideoPlay
} from '@element-plus/icons-vue'
// ElMessage：全局消息提示（成功/警告/错误）
import { ElMessage } from 'element-plus'
// 后端 API：获取 Tab 配置、构建 SSE 流式 URL、文件上传
import {
  getTabConfigs, createSSEStream, uploadFile,
  type TabConfig, type ExampleTemplate
} from '@/api/ai/chat'

/**
 * 每个选项卡对应的示例问题图标集合（按索引区分）
 * chat：智聊类（💡 想法、🔍 搜索、🎯 目标）
 * translate：翻译类（🌐 全球、🔤 字母、📖 词典）
 * meeting：会议类（📝 备忘、📋 清单、🎤 麦克风）
 * writing：写作类（✍️ 书写、📄 文档、📊 报表）
 */
const exampleIconSets: Record<string, string[]> = {
  chat: ['💡', '🔍', '🎯'],
  translate: ['🌐', '🔤', '📖'],
  meeting: ['📝', '📋', '🎤'],
  writing: ['✍️', '📄', '📊']
}

/**
 * 根据示例索引返回对应图标
 * @param idx 示例在列表中的索引
 * @returns 对应的 emoji 图标字符串
 */
function getExampleIcon(idx: number): string {
  // 当前选项卡不存在图标集时，回退到 chat 的图标集
  const icons = exampleIconSets[activeTab.value] || exampleIconSets.chat
  // 通过取模支持索引超过图标集长度的情况
  return icons[idx % icons.length]
}

/**
 * 固定的三个子选项卡配置（名称前端固定，不可变更）
 * key：与后端通信的类型标识
 * name：展示名称
 * icon：emoji 图标
 * examples：示例问题列表，初始化为空，onMounted 时从后端获取覆盖
 */
const subTabs = ref<TabConfig[]>([
  { key: 'translate', name: '翻译', icon: '🌐', examples: [] },
  { key: 'meeting', name: '会议纪要', icon: '📝', examples: [], templates: [
    { key: 'meeting-tpl1', name: '模板1', examples: ['生成一份季度董事会会议纪要模板', '整理高管会议的会议纪要'] },
    { key: 'meeting-tpl2', name: '模板2', examples: ['帮我整理产品需求评审会的会议纪要', '整理技术架构评审会议纪要'] },
    { key: 'meeting-tpl3', name: '模板3', examples: ['生成项目启动会的会议纪要框架', '整理月度例会会议纪要'] }
  ] },
  { key: 'writing', name: '辅助写作', icon: '✍️', examples: [] }
])

/**
 * 所有选项卡（含主选项卡「小信智聊」）
 * 主选项卡位于第一位，子选项卡展开追加
 * 用于在模板中根据 activeTab 找到对应的 TabConfig 对象
 */
const allTabs = ref<TabConfig[]>([
  { key: 'chat', name: '小信智聊', icon: '💬', examples: [] },
  ...subTabs.value
])

/**
 * 子选项卡图标颜色映射（主选项卡不在此映射中，使用主题蓝色）
 * translate：蓝色 #3b82f6
 * meeting：橙色 #f59e0b
 * writing：紫色 #8b5cf6
 */
const tabIconColors: Record<string, string> = {
  translate: '#3b82f6', // 蓝色
  meeting: '#f59e0b',   // 橙色
  writing: '#8b5cf6'    // 紫色
}

// ============ 响应式状态变量 ============

const showMini = ref(false)        // 是否显示小窗口（false 时只显示 FAB 按钮）
const loading = ref(false)         // 是否正在等待 AI 回复
const activeTab = ref('chat')      // 当前激活的选项卡 key（默认 chat）
const inputText = ref('')          // 输入框文本内容
const lastUserQuestion = ref('')   // 最近一次用户提问内容（用于显示气泡）
const currentAnswer = ref('')      // 当前 AI 回复内容（流式更新中实时变化）
const chatBodyRef = ref<HTMLElement | null>(null)  // 对话区滚动容器引用，用于流式输出时自动滚动到底部

/**
 * 流式输出时自动滚动到底部
 * 监听 currentAnswer 变化（流式增量更新），nextTick 后将滚动条滚到底部
 */
watch(currentAnswer, () => {
  nextTick(() => {
    const el = chatBodyRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
})

/**
 * 每个选项卡激活的模板 key 映射（key=tabKey, value=templateKey）
 * 用于不同选项卡独立保存用户选择的模板状态
 */
const activeTemplateMap = ref<Record<string, string>>({})

const uploading = ref(false)        // 文件上传中状态
const fileInputRef = ref<HTMLInputElement | null>(null)  // 隐藏 file input 的引用
const showVideoPlayer = ref(false)  // 是否显示视频播放覆盖层
const videoRef = ref<HTMLVideoElement | null>(null)  // video 元素引用

/**
 * 已上传文件的类型定义
 * id：唯一标识（时间戳+随机串）
 * fileName：文件名（来自后端转换后的名）
 * text：文件转换后的文本内容
 * size：原始文件大小（字节）
 * type：文件类型分类（txt 文本 / audio 音频）
 */
interface UploadedFile {
  id: string
  fileName: string
  text: string
  size: number
  type: 'txt' | 'audio'
}
const uploadedFiles = ref<UploadedFile[]>([])  // 已上传文件列表

// ============ 计算属性 ============

// 当前激活的 TabConfig 对象
const currentTab = computed(() => allTabs.value.find(t => t.key === activeTab.value))
// 当前 Tab 的模板列表（来自后端 templates 配置）
const currentTemplates = computed<ExampleTemplate[]>(() => currentTab.value?.templates || [])

/**
 * 当前激活的模板 key
 * 优先使用 activeTemplateMap 中保存的值；若不存在则取第一个模板
 * 若无任何模板则返回 null
 */
const activeTemplateKey = computed<string | null>(() => {
  if (!currentTemplates.value.length) return null
  const stored = activeTemplateMap.value[activeTab.value]
  if (stored && currentTemplates.value.some(t => t.key === stored)) return stored
  return currentTemplates.value[0].key
})

// 当前激活的 ExampleTemplate 对象
const activeTemplate = computed<ExampleTemplate | null>(() =>
  activeTemplateKey.value ? currentTemplates.value.find(t => t.key === activeTemplateKey.value) || null : null
)

/**
 * 当前要展示的示例问题列表
 * 若有激活的模板，则使用该模板下的 examples；否则使用 Tab 的 examples
 */
const currentTabExamples = computed<string[]>(() => {
  if (activeTemplate.value) return activeTemplate.value.examples
  return currentTab.value?.examples || []
})

// 输入框 placeholder 文案（按选项卡类型切换）
const inputPlaceholder = computed(() => {
  const names: Record<string, string> = {
    chat: '请输入问题，与智聊对话...',
    translate: '请输入要翻译的内容...',
    meeting: '请描述会议相关信息...',
    writing: '请描述写作需求...'
  }
  return names[activeTab.value] || '请输入内容...'
})

// AI 回复内容经 Markdown 渲染后的 HTML（用于 v-html 输出）
const renderedAnswer = computed(() => renderMarkdown(currentAnswer.value))

// ============ 工具方法 ============

/**
 * 简易 Markdown → HTML 转换函数（非完整 Markdown 规范，仅支持常用语法）
 * 支持的语法：
 *   - 代码块：```lang ... ```
 *   - 加粗：**text**
 *   - 无序列表：- 或 * 开头
 *   - 有序列表：1. 开头
 *   - 标题：### / ## / #
 *   - 表格：| cell | cell |
 *   - 引用：> text
 *   - 换行：\n → <br/>
 * @param text 原始 Markdown 文本
 * @returns 转换后的 HTML 字符串
 */
function renderMarkdown(text: string): string {
  if (!text) return ''
  let html = text
  // 代码块：```lang\ncode``` → <pre><code>code</code></pre>
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre class="ai-md-code"><code>$2</code></pre>')
  // 加粗：**text** → <strong>text</strong>
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  // 无序列表项：- 或 * 开头 → <li>
  html = html.replace(/^\s*[-*]\s+(.+)$/gm, '<li>$1</li>')
  // 连续的 <li> 包装成 <ul>
  html = html.replace(/(<li>.*<\/li>\s*)+/g, '<ul>$&</ul>')
  // 有序列表项：1. 开头 → <li>
  html = html.replace(/^\s*\d+\.\s+(.+)$/gm, '<li>$1</li>')
  // 三级标题：### → <h4>
  html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>')
  // 二级标题：## → <h3>
  html = html.replace(/^## (.+)$/gm, '<h3>$1</h3>')
  // 一级标题：# → <h2>
  html = html.replace(/^# (.+)$/gm, '<h2>$1</h2>')
  // 表格行：| ... | → <tr>...</tr>
  html = html.replace(/^\|(.+)\|$/gm, '<tr>$1</tr>')
  // 连续 <tr> 包装成 <table>
  html = html.replace(/(<tr>.*<\/tr>\s*)+/g, '<table class="ai-md-table">$&</table>')
  // 引用：> text → <blockquote>text</blockquote>
  html = html.replace(/^>\s+(.+)$/gm, '<blockquote>$1</blockquote>')
  // 换行：\n → <br/>
  html = html.replace(/\n/g, '<br/>')
  return html
}

// ============ 业务方法 ============

/**
 * 切换选项卡
 * - 更新 activeTab 至目标选项卡
 * - 清空对话内容（lastUserQuestion / currentAnswer）
 * - 若该选项卡有模板但未初始化，默认选中第一个模板
 * @param key 目标选项卡的 key（chat/translate/meeting/writing）
 */
function switchTab(key: string) {
  activeTab.value = key
  lastUserQuestion.value = ''
  currentAnswer.value = ''
  const tab = allTabs.value.find(t => t.key === key)
  if (tab?.templates?.length && !activeTemplateMap.value[key]) {
    activeTemplateMap.value[key] = tab.templates[0].key
  }
}

/**
 * 选择模板（切换示例集）
 * 加载中不允许切换，避免流式中断引起的状态不一致
 * @param tplKey 目标模板 key
 */
function selectTemplate(tplKey: string) {
  if (loading.value) return
  activeTemplateMap.value[activeTab.value] = tplKey
}

/** 打开小窗口（点击 FAB 入口调用） */
function openMini() {
  showMini.value = true
}

/** 关闭小窗口（点击右上角关闭按钮调用） */
function closeMini() {
  showMini.value = false
}

/**
 * 在新窗口打开大页面（AiChatFull.vue）
 * 通过 window.open 跳转到 /ai/chat 路由
 */
function openFullPage() {
  const routeData = window.location.origin + '/ai/chat'
  window.open(routeData, '_blank')
}

/** 播放视频按钮回调：显示视频播放覆盖层并自动播放 */
function handlePlayVideo() {
  showVideoPlayer.value = true
}

/**
 * 视频播放结束回调
 * 关闭覆盖层并回到示例问题页面（清空对话内容）
 */
function handleVideoEnd() {
  showVideoPlayer.value = false
  lastUserQuestion.value = ''
  currentAnswer.value = ''
}

/** 手动关闭视频播放覆盖层 */
function handleCloseVideo() {
  showVideoPlayer.value = false
  // 停止视频播放
  if (videoRef.value) {
    videoRef.value.pause()
    videoRef.value.currentTime = 0
  }
}

/**
 * 处理发送按钮 / 回车发送
 * - 收集输入框文本和已上传文件
 * - 文件内容会拼接为「【文件类型 - 文件名】\n内容」格式
 * - 文件 + 文本组合时格式：「文件块 + 用户问题」
 * - 清空 uploadedFiles 后调用 sendMessage 发送
 */
async function handleSend() {
  const userInput = inputText.value.trim()
  const hasFiles = uploadedFiles.value.length > 0
  // 输入和文件都为空、或正在加载中则忽略
  if ((!userInput && !hasFiles) || loading.value) return

  let question = userInput
  if (hasFiles) {
    // 把所有已上传文件按格式拼接
    const fileTexts = uploadedFiles.value.map(f => {
      const label = f.type === 'audio' ? '语音转文字内容' : '文件内容'
      return `【${label} - ${f.fileName}】\n${f.text}`
    }).join('\n\n')
    if (userInput) {
      // 文件 + 用户问题
      question = `${fileTexts}\n\n【用户问题】\n${userInput}`
    } else {
      // 仅文件
      question = fileTexts
    }
  }

  uploadedFiles.value = []
  await sendMessage(question)
}

/**
 * 点击示例问题：填入输入框供用户编辑，不直接发送
 * @param example 示例问题文本
 */
function sendExample(example: string) {
  if (loading.value) return
  inputText.value = example
}

/**
 * 触发文件选择对话框
 * 仅「会议纪要」选项卡可用，上传中或非 meeting 选项卡时拒绝
 */
function triggerFileInput() {
  if (uploading.value || activeTab.value !== 'meeting') return
  fileInputRef.value?.click()
}

/**
 * 处理文件上传
 * - 校验文件后缀（.txt + 6 种音频格式）
 * - 调用 uploadFile API 上传至后端进行解析（音频转文字）
 * - 上传成功后加入 uploadedFiles 列表展示卡片
 * - 上传中显示 Loading 动画
 * @param event input change 事件对象
 */
async function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  target.value = ''  // 清空 input 值，便于重复选择同一文件

  // 文件类型校验
  const allowedExt = ['.txt', '.mp3', '.wav', '.m4a', '.aac', '.ogg', '.flac']
  const fileName = file.name.toLowerCase()
  if (!allowedExt.some(ext => fileName.endsWith(ext))) {
    ElMessage.warning('仅支持 .txt 和音频文件（mp3/wav/m4a/aac/ogg/flac）')
    return
  }

  uploading.value = true
  try {
    const res = await uploadFile(file)
    const text = res.data?.text || ''
    if (text) {
      // 根据 .txt 后缀判断是否为音频文件
      const isAudio = !fileName.endsWith('.txt')
      uploadedFiles.value.push({
        id: Date.now() + Math.random().toString(36).slice(2, 6),
        fileName: res.data?.fileName || file.name,
        text,
        size: file.size,
        type: isAudio ? 'audio' : 'txt'
      })
      ElMessage.success(`文件「${res.data?.fileName || file.name}」已转换完成`)
    } else {
      ElMessage.warning('文件内容为空')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '文件上传失败')
  } finally {
    uploading.value = false
  }
}

/**
 * 移除已上传文件
 * @param id 文件唯一标识
 */
function removeUploadedFile(id: string) {
  uploadedFiles.value = uploadedFiles.value.filter(f => f.id !== id)
}

/**
 * 格式化文件大小展示
 * < 1KB 显示 B，< 1MB 显示 KB，否则显示 MB
 * @param bytes 文件字节数
 */
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

/**
 * 按选项卡类型生成模拟回复（小窗口简化版）
 * 当真实 SSE 请求失败或超时时使用此函数返回模拟内容
 * 包含四种类型的预设回复模板：
 *   - chat：通用问答（核心要点总结 + 建议方案 + 风险提示 + 后续动作）
 *   - translate：多语种翻译（英文、日文）
 *   - meeting：会议纪要（会议信息 + 主要内容 + 决议事项表格）
 *   - writing：辅助写作（根据关键词动态生成邀请函/总结/公众号文章）
 * @param type 选项卡类型
 * @param question 用户问题
 * @returns Markdown 格式的模拟回复字符串
 */
function getMockReplySmall(type: string, question: string): string {
  const replies: Record<string, (q: string) => string> = {
    chat: (q) => `关于您的问题「${q}」，以下是我的解答：

**核心要点总结：**

1. **背景分析**：该问题涉及业务流程、合规要求、技术实现等多个维度，需要综合考量。
2. **建议方案**：
   - 明确问题边界和具体目标
   - 梳理流程节点和关键干系人
   - 制定分阶段的实施计划，确保风险可控
   - 定期回顾和调整策略
3. **风险提示**：注意数据安全、合规审查以及变更管理。
4. **后续动作**：建议先进行小范围试点，收集反馈后再逐步推广落地。

如需进一步细化某个环节，欢迎随时提问！`,
    translate: (q) => `以下是为您提供的翻译结果：

**原文**：${q.replace(/^将这段中文翻译成地道的英文：?|^翻译成[^：]*：?/g, '').trim()}

**译文 (English)**：
> Artificial intelligence is reshaping industries at an unprecedented pace, bringing profound changes to how we live and work.

**译文 (日本語)**：
> 人工知能は世界中の産業に前例のないスピードで浸透し、私たちの生活と仕事の在り方に根本的な変革をもたらしています。

如需调整翻译风格或增加其他语种，请告诉我。`,
    meeting: (q) => `📋 会议纪要整理如下：

**会议基本信息**
- 主题：${q.includes('模板') ? '季度董事会会议' : q.replace(/帮我整理|会议纪要|的|生成/g, '').trim() || '产品需求评审会议'}
- 时间：2026年8月XX日 14:00 - 16:00
- 参会：王XX（CEO）、陈XX（CTO）、刘XX（CFO）等

**主要内容**
1. 经营业绩回顾：营收增长12.5%，超预期
2. 关键项目进展：A项目进度75%，按计划推进
3. 下季度方向：重点发力AI产品线，加强客户成功
4. 风险提示：核心人才招聘进度滞后

**决议事项（Action Items）**
| 事项 | 负责人 | 截止日期 |
|------|--------|----------|
| 制定AI产品线规划 | CTO | 9月10日 |
| 启动专项招聘计划 | HRD | 8月30日 |
| 数据合规自查专项 | 法务部 | 9月15日 |

下次会议：9月5日跟进以上事项进度。`,
    writing: (q) => `以下是为您撰写的内容：

# ${q.includes('邀请函') ? '商务合作邀请函' : q.includes('总结') ? '年终总结报告' : q.includes('公众号') ? '数字化转型公众号文章' : '商务文书'}

${q.includes('邀请函') ? `
尊敬的[合作方名称]：

展信佳！诚挚邀请贵司参与我司于2026年9月18日（周五）举办的**「2026年度战略合作伙伴大会」**。

📌 **大会议程**：
- 14:00 主题演讲：行业趋势与市场机遇
- 15:30 战略合作签约仪式
- 18:00 答谢晚宴

📇 请于9月10日前联系客户成功经理报名。

期待与您携手，共创未来！

顺祝商祺！
[公司名称] 敬邀
` : ''}

${q.includes('总结') ? `
**一、年度业绩完成情况**
| 指标 | 目标 | 实际 | 完成率 |
|------|------|------|--------|
| 营收 | 1.2亿 | 1.35亿 | 112.5% |
| 新签客户 | 100家 | 128家 | 128% |

**二、重点成果**：完成C轮融资；荣获「最佳创新企业」奖。

**三、下年度计划**：营收同比增长30%；加大AI研发投入。

感谢全体同事的辛勤付出！
` : ''}

${q.includes('公众号') ? `
## 拥抱AI时代：企业数字化转型的三条法则

过去两年，数字化转型从「选择题」变成了「生存题」。我们服务300+客户后总结出三条法则：

🎯 **法则一**：业务价值优先，不要盲目追求技术先进性。
🚀 **法则二**：小步快跑，MVP+迭代，拒绝「一步到位」的大项目。
🧭 **法则三**：一把手工程 + 专业团队，缺一不可。

数字化转型是长征，没有终点，但每一步都算数。最重要的是——现在就开始。
` : ''}

如需调整方向、篇幅或补充具体信息，请告诉我。`
  }
  return (replies[type] || replies.chat)(question)
}

/**
 * 发送消息核心函数（带真实 SSE 流式请求 + 模拟回退机制）
 *
 * 流程：
 *   1. 设置 loading=true，记录用户问题，清空当前答案
 *   2. 通过 createSSEStream 建立 POST SSE 连接
 *   3. 监听 onMessage / onError 回调
 *   4. 设置 3 秒超时：若 3 秒内未收到任何响应，自动关闭连接走模拟
 *   5. SSE 消息类型：
 *      - conversation：会话 ID（小窗口忽略）
 *      - delta：增量内容，追加到 buffer 并更新 currentAnswer
 *      - complete：完整内容，关闭连接
 *      - error：错误信息，显示 ❌ + 内容
 *   6. 完成后 loading=false
 *
 * @param question 用户问题（可能包含文件内容拼接）
 */
async function sendMessage(question: string) {
  loading.value = true
  lastUserQuestion.value = question
  currentAnswer.value = ''
  inputText.value = ''

  try {
    // 建立 POST SSE 流式连接
    let buffer = ''             // 累积接收的内容
    let stream: { close: () => void } | null = null

    // 等待 SSE 完成或出错
    await new Promise<null>((resolve) => {
      stream = createSSEStream(
        { question, type: activeTab.value },
        // onMessage
        (data) => {
          if (data.type === 'conversation' && data.conversationId) {
            // 会话 ID 信息，小窗口不需要持久化，忽略
          } else if (data.type === 'delta') {
            // 增量内容：追加到 buffer 并实时更新显示
            buffer += data.content
            currentAnswer.value = buffer
          } else if (data.type === 'complete') {
            // 完整内容：取 data.content 或 buffer，关闭连接
            currentAnswer.value = data.content || buffer
            stream?.close()
            resolve(null)
          } else if (data.type === 'error') {
            // 错误：显示错误图标 + 内容
            currentAnswer.value = '❌ ' + data.content
            stream?.close()
            resolve(null)
          }
        },
        // onError
        () => {
          if (buffer && !currentAnswer.value) {
            currentAnswer.value = buffer
          }
          resolve(null)
        },
        // onDone（流自然结束）
        () => { resolve(null) }
      )
    })
  } catch (e) {
    console.error('流式请求失败', e)
    if (!currentAnswer.value) {
      currentAnswer.value = '❌ 请求失败，请稍后重试'
    }
  }

  loading.value = false
}

/**
 * 组件挂载时初始化
 * - 优先调用 getTabConfigs 从后端获取 Tab 配置（含示例问题、模板）
 * - 用后端数据覆盖前端固定 Tab 的 examples 和 templates
 * - 失败时使用前端内置的默认示例问题，保证基本可用
 */
onMounted(async () => {
  try {
    const res = await getTabConfigs()
    const backendTabs = res.data || []
    // 遍历后端返回的 Tab 配置，匹配前端固定 Tab 并覆盖 examples（templates 使用前端固定值不覆盖）
    backendTabs.forEach((bt: TabConfig) => {
      const tab = allTabs.value.find(t => t.key === bt.key)
      if (tab) {
        if (bt.examples?.length) tab.examples = bt.examples
      }
    })
  } catch (e) {
    // 后端不可用时使用前端默认示例
    console.error('加载 Tab 配置失败', e)
    const defaults: Record<string, string[]> = {
      chat: ['解读银行日常业务需要遵守的合规管理要求', '帮我梳理一下最近科技行业的发展趋势', '如何提升团队协作效率？有哪些实用方法'],
      translate: ['将这段中文翻译成地道的英文：人工智能正在改变世界', '翻译成日语：这份报告需要在下周五之前完成', '翻译成法语：欢迎来到我们的新产品发布会'],
      meeting: ['生成一份季度董事会会议纪要模板', '帮我整理产品需求评审会的会议纪要', '生成项目启动会的会议纪要框架'],
      writing: ['帮我写一封正式的商务合作邀请函', '撰写一份年终总结报告的大纲', '写一篇关于数字化转型的公众号文章']
    }
    allTabs.value.forEach(t => {
      if (!t.examples?.length) {
        t.examples = defaults[t.key] || []
      }
    })
  }
})
</script>

<style scoped>
/**
 * 样式总览
 * 设计风格：圆角 + 渐变 + 阴影，蓝色主色（#2563eb）
 * 命名规范：BEM（block__element--modifier）
 * 主要分区：
 *   - .ai-widget       ：根容器（fixed 定位右下角）
 *   - .ai-widget__fab  ：悬浮入口按钮（脉冲动画）
 *   - .ai-widget__mini ：小窗口主体（430×600）
 *   - 头部、子选项卡、内容区、对话区、文件区、输入区
 * 响应式：max-height: 88vh 防止超出视窗
 */

/* 根容器：固定定位在页面右下角 */
.ai-widget {
  position: fixed;
  bottom: 20px;
  right: 20px;
  z-index: 9999;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

/* 悬浮入口按钮 */
.ai-widget__fab {
  position: fixed;
  bottom: 32px;
  right: 32px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  box-shadow: 0 4px 20px rgba(102, 126, 234, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.3s ease, box-shadow 0.3s ease;
  z-index: 9999;
}

.ai-widget__fab:hover {
  transform: scale(1.1);
  box-shadow: 0 6px 28px rgba(102, 126, 234, 0.55);
}

.ai-widget__fab:active {
  transform: scale(0.95);
}

.ai-widget__fab-icon {
  font-size: 28px;
  line-height: 1;
  user-select: none;
}

.ai-widget__fab-pulse {
  position: absolute;
  top: 0;
  left: 0;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: 2px solid rgba(102, 126, 234, 0.4);
  animation: fab-pulse 2s infinite;
  pointer-events: none;
}

@keyframes fab-pulse {
  0% { transform: scale(1); opacity: 0.8; }
  100% { transform: scale(1.6); opacity: 0; }
}

.ai-widget__mini {
  width: 430px;
  height: 600px;
  max-height: 88vh;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  border: 1px solid #e8e8e8;
  display: flex;
  flex-direction: column;
  position: relative;  /* 视频覆盖层 absolute 定位基准 */
}

/* ========== 顶部头部 ========== */
.ai-widget__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px 8px;
  border-bottom: 1px solid #f0f0f0;
}

/* 主选项卡：小信智聊 */
.ai-widget__tab-primary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 15px;
  font-weight: 600;
  color: #2563eb;
  background: #dbeafe;
  border: 1.5px solid #bfdbfe;
  transition: all 0.25s ease;
  user-select: none;
  box-shadow: 0 1px 4px rgba(37, 99, 235, 0.08);
}

.ai-widget__tab-primary:hover {
  background: #bfdbfe;
}

.ai-widget__tab-primary.is-active {
  color: #fff;
  background: #2563eb;
  border-color: #2563eb;
  box-shadow: 0 3px 10px rgba(37, 99, 235, 0.35);
}

.ai-widget__tab-primary-icon {
  font-size: 17px;
  line-height: 1;
}

.ai-widget__tab-primary-name {
  line-height: 1;
}

/* 操作按钮组 */
.ai-widget__actions {
  display: flex;
  gap: 6px;
  padding-left: 6px;
}

.ai-widget__action-btn {
  cursor: pointer;
  color: #9ca3af;
  transition: color 0.2s, transform 0.2s;
  padding: 6px;
  border-radius: 8px;
}

.ai-widget__action-btn:hover {
  color: #374151;
  background: #f3f4f6;
}

.ai-widget__action-btn.is-close:hover {
  color: #ef4444;
  background: #fee2e2;
}

/* ========== 子选项卡行 ========== */
.ai-widget__tab-bar {
  display: flex;
  padding: 8px 12px 6px;
  border-bottom: 1px solid #f0f0f0;
  gap: 6px;
}

.ai-widget__tab {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 12px;
  color: #666;
  transition: all 0.25s ease;
  white-space: nowrap;
  flex: 1;
  justify-content: center;
  background: #f5f5f5;
  border: 1px solid transparent;
}

.ai-widget__tab:hover {
  background: #e8e8e8;
  color: #333;
}

.ai-widget__tab.is-active {
  background: #fed7aa;
  color: #9a3412;
  font-weight: 500;
  border-color: #fdba74;
  box-shadow: 0 1px 4px rgba(249, 115, 22, 0.15);
}

.ai-widget__tab-icon {
  font-size: 14px;
  line-height: 1;
}

.ai-widget__tab.is-active .ai-widget__tab-icon {
  transform: scale(1.1);
}

/* ========== 内容区 ========== */
.ai-widget__body {
  flex: 1;
  padding: 8px 14px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
}

.ai-widget__welcome {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-widget__templates {
  display: flex;
  gap: 8px;
  width: 100%;
  justify-content: center;
}

.ai-widget__template {
  flex: 1;
  padding: 5px 10px;
  font-size: 12px;
  border-radius: 999px;
  border: 1px solid #e0e6ef;
  background: #fff;
  color: #555;
  cursor: pointer;
  text-align: center;
  transition: all 0.2s;
  user-select: none;
  max-width: 160px;
}

.ai-widget__template:hover {
  border-color: #2563eb;
  color: #2563eb;
}

.ai-widget__template.is-active {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-color: transparent;
  color: #fff;
  font-weight: 500;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.25);
}

.ai-widget__examples {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  align-items: center;
  margin-top: 80px;
}

.ai-widget__example {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 10px 14px;
  background: #f5f7fa;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 13px;
  color: #333;
  width: 100%;
}

.ai-widget__example-text {
  text-align: left;
}

.ai-widget__example:hover {
  background: #e8f0ff;
  transform: translateY(-1px);
}

.ai-widget__example-icon {
  font-size: 15px;
  flex-shrink: 0;
}

/* ========== 对话区 ========== */
.ai-widget__chat {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-widget__user-msg {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-direction: row-reverse;
}

.ai-widget__user-icon {
  font-size: 18px;
}

.ai-widget__user-text {
  background: #2563eb;
  color: #fff;
  padding: 8px 12px;
  border-radius: 12px 12px 2px 12px;
  max-width: 80%;
  word-break: break-word;
  font-size: 13px;
}

.ai-widget__assistant-msg {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.ai-widget__ai-icon {
  font-size: 18px;
  flex-shrink: 0;
}

.ai-widget__ai-content {
  flex: 1;
  min-width: 0;
}

.ai-widget__markdown {
  background: #f5f7fa;
  padding: 10px 14px;
  border-radius: 4px 12px 12px 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #333;
  word-break: break-word;
}

.ai-widget__markdown :deep(h2),
.ai-widget__markdown :deep(h3),
.ai-widget__markdown :deep(h4) {
  margin: 8px 0 4px;
  font-size: 14px;
}

.ai-widget__markdown :deep(ul),
.ai-widget__markdown :deep(ol) {
  padding-left: 20px;
  margin: 4px 0;
}

.ai-widget__markdown :deep(code) {
  background: #e8e8e8;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 12px;
}

.ai-widget__markdown :deep(blockquote) {
  border-left: 3px solid #2563eb;
  padding-left: 8px;
  color: #666;
  margin: 4px 0;
}

.ai-widget__markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
}

.ai-widget__markdown :deep(td),
.ai-widget__markdown :deep(th) {
  border: 1px solid #ddd;
  padding: 6px 8px;
}

/* 打字动画 */
.ai-widget__typing {
  display: flex;
  gap: 4px;
}

.ai-widget__typing span {
  width: 8px;
  height: 8px;
  background: #ccc;
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out both;
}

.ai-widget__typing span:nth-child(1) { animation-delay: -0.32s; }
.ai-widget__typing span:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

/* ========== 文件卡片 ========== */
.ai-widget__file-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 6px 14px 4px;
  width: 100%;
  box-sizing: border-box;
  background: #fff;
}

.ai-widget__file-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 8px;
  font-size: 12px;
}

.ai-widget__file-type {
  font-size: 15px;
  flex-shrink: 0;
}

.ai-widget__file-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.ai-widget__file-name {
  font-weight: 500;
  color: #0c4a6e;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ai-widget__file-meta {
  color: #64748b;
  font-size: 11px;
  margin-top: 2px;
}

.ai-widget__file-remove {
  cursor: pointer;
  color: #94a3b8;
  flex-shrink: 0;
  font-size: 14px;
  transition: color 0.2s;
}

.ai-widget__file-remove:hover {
  color: #ef4444;
}

/* ========== 输入区 ========== */
.ai-widget__input {
  padding: 8px 12px 10px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.ai-widget__file-input {
  display: none;
}

/* + 上传按钮 */
.ai-widget__upload {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #f0f5ff;
  color: #2563eb;
  font-size: 18px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s;
  user-select: none;
}

.ai-widget__upload:hover:not(.is-disabled) {
  background: #dbeafe;
  transform: scale(1.05);
}

.ai-widget__upload.is-disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.ai-widget__upload .is-loading {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 输入框容器（含内嵌发送按钮） */
.ai-widget__input-box {
  flex: 1;
  position: relative;
  display: flex;
  align-items: flex-end;
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  background: #fff;
  transition: border-color 0.2s;
  padding: 2px 4px 2px 0;
}

.ai-widget__input-box:focus-within {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.1);
}

.ai-widget__textarea {
  flex: 1;
  padding: 6px 8px;
}

.ai-widget__textarea :deep(textarea) {
  font-size: 13px;
  line-height: 1.5;
  padding: 4px 6px;
  background: transparent;
  border: none !important;
  box-shadow: none !important;
}

.ai-widget__textarea :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
}

/* 发送按钮（内嵌在输入框右侧） */
.ai-widget__send {
  flex-shrink: 0;
  height: 30px;
  padding: 0 12px;
  background: #2563eb !important;
  border: none !important;
  border-radius: 8px;
  color: #fff !important;
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.3);
  transition: all 0.2s ease;
  margin: 0;
}

.ai-widget__send:hover:not(:disabled) {
  transform: scale(1.08);
  box-shadow: 0 3px 10px rgba(37, 99, 235, 0.45);
}

.ai-widget__send:disabled {
  background: #d1d5db !important;
  box-shadow: none;
}

.ai-widget__footer {
  text-align: left;
  padding: 3px 12px 6px;
  font-size: 11px;
  color: #999;
}

/* ========== 视频播放覆盖层 ========== */
/* 宽度随视频自适应（fit-content），水平居中，消除两侧黑边 */
.ai-widget__video-overlay {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: fit-content;
  background: #000;
  z-index: 100;
  border-radius: 16px;
  overflow: hidden;
}

/* 视频元素：高度撑满，宽度按比例自适应 */
.ai-widget__video {
  height: 100%;
  width: auto;
  display: block;
}

/* 播放视频时加高小窗口，并隐藏小窗口本身（背景透明、无边框无阴影） */
.ai-widget__mini.is-playing-video {
  height: 800px;
  max-height: 95vh;
  background: transparent;
  border: none;
  box-shadow: none;
}

/* 右上角关闭按钮：半透明圆形背景 */
.ai-widget__video-close {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 101;
  transition: background 0.2s;
}

.ai-widget__video-close:hover {
  background: rgba(0, 0, 0, 0.75);
}
</style>
