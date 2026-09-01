import request from '@/utils/request'

export interface AiConversation {
  id: number
  title: string
  type: string
  isPinned: string
  userId: number
  createTime: string
  updateTime: string
}

export interface AiMessage {
  id: number
  conversationId: number
  role: string
  content: string
  type: string
  isComplete: string
  createTime: string
}

export interface ExampleTemplate {
  key: string
  name: string
  examples: string[]
}

export interface TabConfig {
  key: string
  name: string
  icon: string
  examples: string[]
  templates?: ExampleTemplate[]
}

// 获取会话列表
export const listConversations = () => {
  return request.get('/ai/chat/conversations')
}

// 获取会话消息
export const getMessages = (id: number) => {
  return request.get(`/ai/chat/conversations/${id}/messages`)
}

// 创建会话
export const createConversation = (data: { title: string; type: string }) => {
  return request.post('/ai/chat/conversations', data)
}

// 删除会话
export const deleteConversation = (id: number) => {
  return request.delete(`/ai/chat/conversations/${id}`)
}

// 切换置顶
export const togglePin = (id: number) => {
  return request.post(`/ai/chat/conversations/${id}/pin`)
}

// 更新标题
export const updateTitle = (id: number, title: string) => {
  return request.put(`/ai/chat/conversations/${id}/title`, { title })
}

// 获取 Tab 配置
export const getTabConfigs = () => {
  return request.get('/ai/chat/tabs')
}

// 上传文件（txt/音频），返回转换后的文字
export const uploadFile = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/ai/chat/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 创建 POST SSE 流式连接（fetch + ReadableStream，替代 EventSource 仅支持 GET 的限制）
export function createSSEStream(
  params: { question: string; conversationId?: number; type: string },
  onMessage: (data: any) => void,
  onError: () => void,
  onDone: () => void
): { close: () => void } {
  const base = import.meta.env.VITE_APP_BASE_API || ''
  const token = localStorage.getItem('Admin-Token') || ''
  const qs = new URLSearchParams()
  qs.set('question', params.question)
  qs.set('type', params.type)
  qs.set('token', token)
  if (params.conversationId) {
    qs.set('conversationId', String(params.conversationId))
  }
  const url = `${base}/ai/chat/stream?${qs.toString()}`

  const controller = new AbortController()

  ;(async () => {
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Accept': 'text/event-stream' },
        signal: controller.signal
      })
      if (!response.ok || !response.body) {
        onError()
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim()
            if (!dataStr) continue
            try {
              onMessage(JSON.parse(dataStr))
            } catch (e) {
              console.error('SSE 解析错误', e)
            }
          }
        }
      }
      onDone()
    } catch (e: any) {
      if (e.name !== 'AbortError') {
        onError()
      }
    }
  })()

  return { close: () => controller.abort() }
}
