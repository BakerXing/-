<template>
  <div>
    <div v-if="!showWidget" class="ai-fab" @click="openWidget">
      <svg viewBox="0 0 24 24" width="28" height="28" fill="white">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
      </svg>
    </div>

    <div v-if="showWidget" class="ai-widget" :style="widgetStyle">
      <div class="ai-widget-header">
        <div class="ai-widget-tabs">
          <div class="ai-tab-row">
            <div
              :class="['ai-tab', 'ai-tab-large', activeTab === '1' ? 'active' : '']"
              @click="switchTab('1')"
            >
              <span class="ai-tab-icon">💬</span>
              <span class="ai-tab-text">智聊</span>
            </div>
          </div>
          <div class="ai-tab-divider"></div>
          <div class="ai-tab-row ai-tab-row-small">
            <div
              :class="['ai-tab', 'ai-tab-small', activeTab === '2' ? 'active' : '']"
              @click="switchTab('2')"
            >
              <span>🌐</span>
              <span class="ai-tab-text">翻译</span>
            </div>
            <div
              :class="['ai-tab', 'ai-tab-small', activeTab === '3' ? 'active' : '']"
              @click="switchTab('3')"
            >
              <span>📝</span>
              <span class="ai-tab-text">会议纪要</span>
            </div>
            <div
              :class="['ai-tab', 'ai-tab-small', activeTab === '4' ? 'active' : '']"
              @click="switchTab('4')"
            >
              <span>✍️</span>
              <span class="ai-tab-text">辅助写作</span>
            </div>
          </div>
        </div>
        <div class="ai-widget-actions">
          <button class="ai-action-btn" title="详情" @click="playDetailVideoInChat">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
              <path d="M8 5v14l11-7z"/>
            </svg>
          </button>
          <button class="ai-action-btn" title="展开" @click="expandToPage">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
              <path d="M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"/>
            </svg>
          </button>
          <button class="ai-action-btn" title="关闭" @click="closeWidget">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
            </svg>
          </button>
        </div>
      </div>

      <div class="ai-widget-messages" ref="messagesContainer" v-if="!playingVideo">
        <div v-if="messages.length === 0" class="ai-empty-state">
          <div class="ai-empty-icon">{{ tabIcon }}</div>
          <div class="ai-empty-text">你好，我是{{ tabName }}助手，有什么可以帮您？</div>
          <div class="ai-suggestions">
            <div
              v-for="(item, idx) in currentSuggestions"
              :key="idx"
              class="ai-suggestion-item"
              @click="applySuggestion(item)"
            >
              <span class="ai-suggestion-icon">{{ item.icon }}</span>
              <span class="ai-suggestion-text">{{ item.text }}</span>
            </div>
          </div>
        </div>
        <div v-for="msg in messages" :key="msg.messageId || msg.tempId" :class="['ai-msg', msg.role]">
          <div class="ai-msg-avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
          <div class="ai-msg-content">
            <template v-if="msg.type === 'video'">
              <div class="ai-msg-video-thumb" @click="playVideoFullscreen(msg)">
                <video class="ai-msg-video-thumb-inner" :src="msg.videoUrl" muted></video>
                <div class="ai-msg-video-play">
                  <svg viewBox="0 0 24 24" width="36" height="36" fill="#fff">
                    <path d="M8 5v14l11-7z"/>
                  </svg>
                </div>
              </div>
            </template>
            <template v-else>
              <div v-if="msg.role === 'user'" class="ai-msg-bubble">{{ msg.content }}</div>
              <div v-else class="ai-msg-bubble ai-msg-assistant">{{ msg.content || '正在思考...' }}</div>
            </template>
          </div>
        </div>
      </div>

      <div v-if="playingVideo" class="ai-video-fullscreen">
        <div class="ai-video-fullscreen-header">
          <span class="ai-video-fullscreen-title">详情介绍</span>
          <button class="ai-video-fullscreen-close" @click="resetToInitialState" title="关闭">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
            </svg>
          </button>
        </div>
        <video
          ref="fullscreenVideo"
          class="ai-video-fullscreen-player"
          :src="playingVideo.videoUrl"
          controls
          autoplay
          @loadedmetadata="onVideoMetadataLoaded"
          @ended="resetToInitialState"
        ></video>
      </div>

      <div class="ai-widget-input">
        <div class="ai-widget-input-toolbar">
          <button class="ai-widget-upload-btn" @click="triggerUpload">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
              <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/>
            </svg>
            上传
          </button>
          <input ref="fileInput" type="file" style="display: none" @change="handleFileUpload" multiple />
        </div>
        <div class="ai-widget-input-main">
          <textarea
            ref="inputArea"
            v-model="inputText"
            :placeholder="'请输入内容，与' + tabName + '对话...'"
            @keydown="handleKeydown"
            :disabled="isStreaming"
            rows="2"
          ></textarea>
          <button :disabled="!inputText.trim() || isStreaming" @click="sendMessage">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
            </svg>
          </button>
        </div>
        <div class="ai-widget-input-hint">Enter 发送，Shift + Enter 换行</div>
      </div>
    </div>
  </div>
</template>

<script>
import { getToken } from '@/utils/auth'
import { addConversation, addMessage, myConversationList, listMessage } from '@/api/ai/robot'

export default {
  name: 'AiChatWidget',
  data() {
    return {
      showWidget: false,
      detailVideoUrl: '/videos/中信数字人走出视频.mp4',
      playingVideo: null,
      widgetWidth: 380,
      widgetHeight: 560,
      defaultWidth: 380,
      defaultHeight: 560,
      activeTab: '1',
      inputText: '',
      messages: [],
      currentConversationId: null,
      isStreaming: false,
      eventSource: null,
      tabMap: {
        '1': { name: '智聊', icon: '💬' },
        '2': { name: '翻译', icon: '🌐' },
        '3': { name: '会议纪要', icon: '📝' },
        '4': { name: '辅助写作', icon: '✍️' }
      },
      suggestionsMap: {
        '1': [
          { icon: '💡', text: '解释微服务架构' },
          { icon: '📚', text: '推荐Java学习书籍' },
          { icon: '🎯', text: '提高代码质量建议' }
        ],
        '2': [
          { icon: '📄', text: '翻译：欢迎使用产品' },
          { icon: '🇯🇵', text: '翻译日文：今天好' },
          { icon: '🇨🇳', text: '翻译中文：AI改变世界' }
        ],
        '3': [
          { icon: '📋', text: '产品需求评审纪要' },
          { icon: '👥', text: '项目周会纪要模板' },
          { icon: '🎯', text: '整理会议要点待办' }
        ],
        '4': [
          { icon: '📧', text: '写商务邮件' },
          { icon: '📝', text: 'AI科普文章' },
          { icon: '📢', text: '产品发布会演讲稿' }
        ]
      }
    }
  },
  computed: {
    tabName() {
      return this.tabMap[this.activeTab]?.name || '智聊'
    },
    tabIcon() {
      return this.tabMap[this.activeTab]?.icon || '💬'
    },
    currentSuggestions() {
      return this.suggestionsMap[this.activeTab] || []
    },
    widgetStyle() {
      return {
        width: this.widgetWidth + 'px',
        height: this.widgetHeight + 'px',
        transition: 'width 0.3s ease, height 0.3s ease'
      }
    }
  },
  methods: {
    playDetailVideoInChat() {
      const videoMsg = {
        tempId: 'video-' + Date.now(),
        role: 'assistant',
        type: 'video',
        videoUrl: this.detailVideoUrl,
        content: ''
      }
      this.messages.push(videoMsg)
      this.$nextTick(() => {
        this.scrollToBottom()
        this.playVideoFullscreen(videoMsg)
      })
    },
    playVideoFullscreen(msg) {
      this.playingVideo = msg
      this.$nextTick(() => {
        if (this.$refs.fullscreenVideo) {
          this.$refs.fullscreenVideo.play().catch(() => {})
        }
      })
    },
    onVideoMetadataLoaded(e) {
      const video = e.target
      const videoWidth = video.videoWidth
      const videoHeight = video.videoHeight
      const headerHeight = 44
      const minWidth = 280
      const minHeight = 320
      const maxWidth = Math.min(window.innerWidth - 48, 800)
      const maxHeight = Math.min(window.innerHeight - 48, 700)
      let targetWidth = videoWidth
      let targetHeight = videoHeight + headerHeight
      if (targetWidth > maxWidth) {
        const ratio = maxWidth / videoWidth
        targetWidth = maxWidth
        targetHeight = Math.round(videoHeight * ratio) + headerHeight
      }
      if (targetHeight > maxHeight) {
        const ratio = maxHeight / (videoHeight + headerHeight)
        targetHeight = maxHeight
        targetWidth = Math.round(videoWidth * ratio)
      }
      this.widgetWidth = Math.max(Math.min(targetWidth, maxWidth), minWidth)
      this.widgetHeight = Math.max(Math.min(targetHeight, maxHeight), minHeight)
    },
    closeVideoFullscreen() {
      if (this.$refs.fullscreenVideo) {
        this.$refs.fullscreenVideo.pause()
      }
      this.playingVideo = null
      this.widgetWidth = this.defaultWidth
      this.widgetHeight = this.defaultHeight
    },
    resetToInitialState() {
      if (this.$refs.fullscreenVideo) {
        this.$refs.fullscreenVideo.pause()
      }
      this.playingVideo = null
      this.widgetWidth = this.defaultWidth
      this.widgetHeight = this.defaultHeight
      this.messages = []
      this.currentConversationId = null
      this.inputText = ''
      this.closeEventSource()
    },
    applySuggestion(item) {
      this.inputText = item.text
      this.$nextTick(() => {
        this.$refs.inputArea.focus()
      })
    },
    handleKeydown(e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        this.sendMessage()
      }
    },
    triggerUpload() {
      this.$refs.fileInput.click()
    },
    handleFileUpload(e) {
      const files = e.target.files
      if (files && files.length > 0) {
        const names = Array.from(files).map(f => f.name).join(', ')
        this.$message.success('已选择文件：' + names)
      }
      e.target.value = ''
    },
    openWidget() {
      this.showWidget = true
    },
    closeWidget() {
      this.showWidget = false
      this.closeEventSource()
      if (this.$refs.fullscreenVideo) {
        this.$refs.fullscreenVideo.pause()
      }
      this.playingVideo = null
      this.widgetWidth = this.defaultWidth
      this.widgetHeight = this.defaultHeight
    },
    expandToPage() {
      this.$router.push('/ai-robot/index')
    },
    switchTab(tab) {
      this.activeTab = tab
      this.currentConversationId = null
      this.messages = []
      this.inputText = ''
      this.closeEventSource()
    },
    closeEventSource() {
      if (this.eventSource) {
        this.eventSource.close()
        this.eventSource = null
      }
      this.isStreaming = false
    },
    async sendMessage() {
      const text = this.inputText.trim()
      if (!text || this.isStreaming) return
      this.isStreaming = true
      const userMsg = {
        tempId: Date.now(),
        role: 'user',
        content: text
      }
      this.messages.push(userMsg)
      this.inputText = ''
      this.$nextTick(() => this.scrollToBottom())

      try {
        if (!this.currentConversationId) {
          const convRes = await addConversation({
            title: text.length > 20 ? text.substring(0, 20) : text,
            conversationType: this.activeTab
          })
          this.currentConversationId = convRes.data
        }

        await addMessage({
          conversationId: this.currentConversationId,
          role: 'user',
          content: text
        })

        const aiTempId = Date.now() + 1
        this.messages.push({
          tempId: aiTempId,
          role: 'assistant',
          content: ''
        })

        this.streamChat(text, aiTempId)
      } catch (e) {
        this.isStreaming = false
        this.$message.error('发送失败：' + (e.msg || e.message))
      }
    },
    streamChat(userText, aiTempId) {
      const token = getToken()
      const url = process.env.VUE_APP_BASE_API + '/ai/robot/chat/stream'

      const xhr = new XMLHttpRequest()
      xhr.open('POST', url, true)
      xhr.setRequestHeader('Content-Type', 'application/json')
      if (token) {
        xhr.setRequestHeader('Authorization', 'Bearer ' + token)
      }

      let received = ''
      xhr.onprogress = () => {
        const newData = xhr.responseText.substring(received.length)
        received = xhr.responseText
        const lines = newData.split('\n')
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.substring(5).trim()
            if (data === '[DONE]') {
              this.isStreaming = false
              return
            }
            if (data) {
              const msg = this.messages.find(m => m.tempId === aiTempId)
              if (msg) {
                msg.content += data
                this.$nextTick(() => this.scrollToBottom())
              }
            }
          }
        }
      }

      xhr.onload = () => {
        this.isStreaming = false
      }

      xhr.onerror = () => {
        this.isStreaming = false
        const msg = this.messages.find(m => m.tempId === aiTempId)
        if (msg && !msg.content) {
          msg.content = '连接失败，请重试'
        }
      }

      xhr.send(JSON.stringify({
        conversationId: this.currentConversationId,
        role: 'user',
        content: userText
      }))
    },
    scrollToBottom() {
      const container = this.$refs.messagesContainer
      if (container) {
        container.scrollTop = container.scrollHeight
      }
    }
  }
}
</script>

<style scoped>
.ai-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #1e3a8a 0%, #3b82f6 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(30, 58, 138, 0.4);
  z-index: 9998;
  transition: transform 0.2s;
}
.ai-fab:hover {
  transform: scale(1.1);
}
.ai-widget {
  position: fixed;
  right: 24px;
  bottom: 24px;
  width: 380px;
  height: 560px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  z-index: 9999;
  overflow: hidden;
}
.ai-widget-header {
  padding: 12px 16px 8px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
}
.ai-widget-tabs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 100px;
}
.ai-tab-row {
  display: flex;
  gap: 8px;
}
.ai-tab-row-small {
  justify-content: flex-start;
}
.ai-tab-divider {
  height: 1px;
  background: #e2e8f0;
  margin: 2px 0;
}
.ai-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 13px;
  color: #64748b;
  background: #f1f5f9;
}
.ai-tab-large {
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 500;
  background: #dbeafe;
  color: #1e40af;
}
.ai-tab-large.active {
  background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%);
  color: #fff;
  box-shadow: 0 2px 8px rgba(30, 58, 138, 0.3);
}
.ai-tab-small {
  padding: 6px 12px;
  font-size: 12px;
  background: #f1f5f9;
  color: #64748b;
}
.ai-tab-small.active {
  background: #bfdbfe;
  color: #1d4ed8;
}
.ai-tab-text {
  white-space: nowrap;
}
.ai-widget-actions {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  gap: 4px;
}
.ai-action-btn {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  color: #64748b;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.ai-action-btn:hover {
  background: #e2e8f0;
  color: #1e293b;
}
.ai-widget-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #fafafa;
}
.ai-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #94a3b8;
}
.ai-empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
}
.ai-empty-text {
  font-size: 14px;
}
.ai-suggestions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 20px;
  width: 100%;
}
.ai-suggestion-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  text-align: left;
}
.ai-suggestion-item:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
}
.ai-suggestion-icon { font-size: 16px; }
.ai-suggestion-text { font-size: 12px; color: #475569; }
.ai-msg {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.ai-msg.user {
  flex-direction: row-reverse;
}
.ai-msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 500;
  flex-shrink: 0;
}
.ai-msg.user .ai-msg-avatar {
  background: #2563eb;
  color: #fff;
}
.ai-msg.assistant .ai-msg-avatar {
  background: #1e3a8a;
  color: #fff;
}
.ai-msg-content {
  max-width: 75%;
}
.ai-msg-bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}
.ai-msg.user .ai-msg-bubble {
  background: #2563eb;
  color: #fff;
  border-top-right-radius: 4px;
}
.ai-msg-assistant {
  background: #fff;
  color: #1e293b;
  border: 1px solid #e2e8f0;
  border-top-left-radius: 4px;
}
.ai-widget-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
}
.ai-widget-input-toolbar {
  display: flex;
}
.ai-widget-upload-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid #e2e8f0;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 11px;
  color: #64748b;
  transition: all 0.2s;
}
.ai-widget-upload-btn:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
}
.ai-widget-input-main {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.ai-widget-input textarea {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
  resize: none;
  transition: border-color 0.2s;
}
.ai-widget-input textarea:focus {
  border-color: #2563eb;
}
.ai-widget-input textarea:disabled {
  background: #f8fafc;
}
.ai-widget-input-main button {
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.2s;
  flex-shrink: 0;
}
.ai-widget-input-main button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.ai-widget-input-hint {
  font-size: 11px;
  color: #94a3b8;
}
.ai-detail-btn {
  /* 与 ai-action-btn 样式一致 */
}
.ai-msg-video-thumb {
  position: relative;
  width: 200px;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: #000;
}
.ai-msg-video-thumb:hover .ai-msg-video-play {
  transform: scale(1.1);
}
.ai-msg-video-thumb-inner {
  width: 100%;
  display: block;
  height: 120px;
  object-fit: cover;
}
.ai-msg-video-play {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 56px;
  height: 56px;
  background: rgba(0, 0, 0, 0.6);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.2s;
}
.ai-video-fullscreen {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: #000;
  display: flex;
  flex-direction: column;
  z-index: 10;
}
.ai-video-fullscreen-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%);
  color: #fff;
  flex-shrink: 0;
}
.ai-video-fullscreen-title {
  font-size: 14px;
  font-weight: 500;
}
.ai-video-fullscreen-close {
  width: 30px;
  height: 30px;
  border: none;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 6px;
  cursor: pointer;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.ai-video-fullscreen-close:hover {
  background: rgba(255, 255, 255, 0.25);
}
.ai-video-fullscreen-player {
  flex: 1;
  width: 100%;
  display: block;
  object-fit: contain;
  background: #000;
}
</style>
