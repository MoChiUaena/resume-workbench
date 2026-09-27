import { defineConfig, type ProxyOptions } from 'vite';
import vue from '@vitejs/plugin-vue';
const target='http://127.0.0.1:18765';
const proxyOptions:ProxyOptions={
  target,changeOrigin:true,
  configure(server){server.on('proxyReq',(outgoing,incoming)=>{
    if(['http://127.0.0.1:5173','http://localhost:5173'].includes(incoming.headers.origin || ''))outgoing.setHeader('Origin',target);
  });}
};
const proxy=Object.fromEntries(['/api','/render','/print.css','/paginate.js','/fonts','/samples'].map(prefix=>[prefix,proxyOptions]));
export default defineConfig({ plugins:[vue()],server:{host:'127.0.0.1',port:5173,strictPort:true,proxy} });
