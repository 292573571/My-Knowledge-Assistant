import { createApp } from 'vue'
import App from './App.vue'
import 'highlight.js/styles/github.css'
import './styles/main.css'
import { installGlobalErrorHandlers } from './utils/clientErrorReporter'

const app = createApp(App)
installGlobalErrorHandlers(app)
app.mount('#app')
