<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-upload
          ref="uploadRef"
          :show-file-list="false"
          :auto-upload="true"
          :http-request="handleUpload"
          accept=".doc,.docx,.txt,.rtf,.odt,.pdf,.xls,.xlsx,.csv,.ods,.ppt,.pptx,.odp"
        >
          <el-button type="primary" :icon="Upload">上传文件</el-button>
        </el-upload>
      </el-col>
      <el-col :span="1.5">
        <el-button :icon="Refresh" @click="getList">刷新</el-button>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="documentList" border>
      <el-table-column label="文件名" prop="name" min-width="240" show-overflow-tooltip>
        <template #default="scope">
          <span class="doc-icon">{{ getIcon(scope.row.extension) }}</span>
          <span>{{ scope.row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" prop="extension" width="90" align="center">
        <template #default="scope">
          <el-tag :type="getTypeTag(scope.row.extension)" size="small">{{ scope.row.extension || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" prop="sizeText" width="110" align="center" />
      <el-table-column label="修改时间" width="180" align="center">
        <template #default="scope">
          <span>{{ parseTime(scope.row.lastModified) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" align="center">
        <template #default="scope">
          <el-button type="primary" link :icon="Edit" @click="openEditor(scope.row, 'edit')">编辑</el-button>
          <el-button type="primary" link :icon="View" @click="openEditor(scope.row, 'preview')">预览</el-button>
          <el-button type="danger" link :icon="Delete" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 全屏编辑器 -->
    <div v-if="editorVisible" class="onlyoffice-fullscreen">
      <div class="onlyoffice-header">
        <span class="onlyoffice-title">{{ editorTitle }}</span>
        <el-icon class="onlyoffice-close" :size="22" @click="closeEditor"><Close /></el-icon>
      </div>
      <div v-loading="editorLoading" element-loading-text="正在加载编辑器..." class="onlyoffice-wrapper">
        <div id="onlyoffice-editor" class="onlyoffice-container"></div>
        <div v-if="editorError" class="editor-error">
          <el-icon :size="48" color="#f56c6c"><Warning /></el-icon>
          <p>{{ editorError }}</p>
          <p class="editor-error-tip" v-if="isConfigError">
            请确认 OnlyOffice 文档服务器已启动，且 <code>application.yml</code> 中 <code>onlyoffice.document-server-url</code> 配置正确。
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Refresh, Edit, View, Delete, Warning, Close } from '@element-plus/icons-vue'
import { listDocument, uploadDocument, getEditorConfig, deleteDocument } from '@/api/tool/onlyoffice'
import { parseTime } from '@/utils/ruoyi'

interface DocumentItem {
  fileName: string
  name: string
  size: number
  sizeText: string
  lastModified: number
  extension: string
}

const loading = ref(false)
const documentList = ref<DocumentItem[]>([])
const editorVisible = ref(false)
const editorLoading = ref(false)
const editorError = ref('')
const isConfigError = ref(false)
const editorTitle = ref('文档编辑')
const uploadRef = ref<any>(null)

let editor: any = null
let scriptLoadPromise: Promise<void> | null = null

const getList = async () => {
  loading.value = true
  try {
    const res: any = await listDocument()
    documentList.value = res.data || []
  } finally {
    loading.value = false
  }
}

const handleUpload = async (option: any) => {
  const formData = new FormData()
  formData.append('file', option.file)
  try {
    await uploadDocument(formData)
    ElMessage.success('上传成功')
    getList()
  } catch (e) {}
}

const openEditor = (row: DocumentItem, mode: string) => {
  editorVisible.value = true
  editorLoading.value = true
  editorError.value = ''
  isConfigError.value = false
  editorTitle.value = mode === 'edit' ? `编辑 - ${row.name}` : `预览 - ${row.name}`
  nextTick(() => {
    initEditor(row, mode)
  })
}

const initEditor = async (row: DocumentItem, mode: string) => {
  try {
    const res: any = await getEditorConfig(row.fileName, mode)
    const data = res.data
    // 后端返回结构：{ config: { documentType, document, editorConfig, token }, documentServerUrl: "..." }
    const signedConfig = data.config || data
    const documentServerUrl = data.documentServerUrl || signedConfig.documentServerUrl
    if (!documentServerUrl) {
      editorLoading.value = false
      editorError.value = '未配置 OnlyOffice 文档服务器地址'
      isConfigError.value = true
      return
    }
    try {
      await loadOnlyOfficeScript(documentServerUrl)
    } catch (e) {
      editorLoading.value = false
      editorError.value = '无法连接 OnlyOffice 文档服务器，请检查服务是否启动及地址配置。'
      isConfigError.value = true
      return
    }
    const editorConfig = {
      documentType: signedConfig.documentType,
      document: signedConfig.document,
      editorConfig: signedConfig.editorConfig,
      width: `${window.innerWidth}px`,
      height: `${window.innerHeight - 54}px`,
      events: {
        onError: (e: any) => {
          console.error('OnlyOffice 错误', e)
        }
      }
    }
    if (signedConfig.token) {
      (editorConfig as any).token = signedConfig.token
    }
    const container = document.getElementById('onlyoffice-editor')
    if (container) {
      container.innerHTML = ''
    }
    editor = new (window as any).DocsAPI.DocEditor('onlyoffice-editor', editorConfig)
    editorLoading.value = false
  } catch (e) {
    editorLoading.value = false
    editorError.value = '获取编辑器配置失败'
  }
}

const loadOnlyOfficeScript = (documentServerUrl: string): Promise<void> => {
  const url = documentServerUrl.replace(/\/$/, '') + '/web-apps/apps/api/documents/api.js'
  if ((window as any).DocsAPI) {
    return Promise.resolve()
  }
  if (scriptLoadPromise) {
    return scriptLoadPromise
  }
  scriptLoadPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = url
    script.async = true
    script.onload = () => {
      if ((window as any).DocsAPI) {
        resolve()
      } else {
        scriptLoadPromise = null
        reject(new Error('DocsAPI 未加载'))
      }
    }
    script.onerror = () => {
      scriptLoadPromise = null
      reject(new Error('脚本加载失败'))
    }
    document.head.appendChild(script)
  })
  return scriptLoadPromise
}

const closeEditor = () => {
  destroyEditor()
  editorVisible.value = false
}

const destroyEditor = () => {
  if (editor) {
    try {
      editor.destroyEditor()
    } catch (e) {
      // ignore
    }
    editor = null
  }
  editorError.value = ''
  isConfigError.value = false
}

const handleDelete = async (row: DocumentItem) => {
  try {
    await ElMessageBox.confirm(`是否确认删除文件 "${row.name}"？`, '系统提示', { type: 'warning' })
    await deleteDocument(row.fileName)
    ElMessage.success('删除成功')
    getList()
  } catch (e) {}
}

const getIcon = (ext: string) => {
  const map: { [key: string]: string } = {
    doc: '📄', docx: '📄', txt: '📄', rtf: '📄', odt: '📄', pdf: '📕',
    xls: '📊', xlsx: '📊', csv: '📊', ods: '📊',
    ppt: '📽️', pptx: '📽️', odp: '📽️'
  }
  return map[ext] || '📄'
}

const getTypeTag = (ext: string) => {
  if (['doc', 'docx', 'txt', 'rtf', 'odt', 'pdf'].includes(ext)) return ''
  if (['xls', 'xlsx', 'csv', 'ods'].includes(ext)) return 'success'
  if (['ppt', 'pptx', 'odp'].includes(ext)) return 'warning'
  return 'info'
}

onMounted(() => {
  getList()
})
</script>

<style scoped>
.app-container {
  padding: 20px 0;
}
.mb8 {
  margin-bottom: 8px;
  padding: 0 20px;
}
.doc-icon {
  margin-right: 6px;
}
.onlyoffice-fullscreen {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  z-index: 2000;
  background: #fff;
  display: flex;
  flex-direction: column;
}
.onlyoffice-header {
  height: 54px;
  padding: 0 20px;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}
.onlyoffice-title {
  font-size: 18px;
  font-weight: 500;
  color: #303133;
}
.onlyoffice-close {
  cursor: pointer;
  color: #606266;
}
.onlyoffice-close:hover {
  color: #409eff;
}
.onlyoffice-wrapper {
  flex: 1;
  overflow: hidden;
  position: relative;
}
.onlyoffice-container {
  width: 100%;
  height: 100%;
}
.editor-error {
  text-align: center;
  padding: 60px 20px;
  color: #f56c6c;
}
.editor-error i {
  font-size: 48px;
}
.editor-error p {
  margin-top: 16px;
  font-size: 15px;
}
.editor-error .editor-error-tip {
  color: #909399;
  font-size: 13px;
}
.editor-error code {
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 3px;
  color: #e6a23c;
}
</style>
