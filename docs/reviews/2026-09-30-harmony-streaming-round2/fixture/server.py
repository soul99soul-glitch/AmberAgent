from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json, time
class Handler(BaseHTTPRequestHandler):
 def do_POST(self):
  self.rfile.read(int(self.headers.get('Content-Length',0)))
  self.send_response(200)
  self.send_header('Content-Type','text/plain' if self.path=='/plain' else 'text/event-stream')
  self.send_header('Cache-Control','no-cache')
  self.end_headers()
  text='# 网络流式验证\n\n'
  for i in range(1,9):
   text+=f'## 第{i}段\n\n'+'这是从实际网络逐包抵达的**中文正文**，含有 *斜体* 与 `行内代码`。旧文字保持不动，新增尾段渐显。'*3+'\n\n'
   if i%2==0: text+='- 第一项逐步更新\n- 第二项继续输出\n\n'
  text+='最终完整标记：网络结束，全文无缺字。'
  try:
   for start in range(0,len(text),12):
    chunk={'id':'network-probe','model':'probe','choices':[{'index':0,'delta':{'content':text[start:start+12]},'finish_reason':None}]}
    self.wfile.write(('data: '+json.dumps(chunk,ensure_ascii=False)+'\n\n').encode());self.wfile.flush();time.sleep(.08)
   self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()
  except (BrokenPipeError,ConnectionResetError): pass
 def log_message(self,fmt,*args): print(fmt%args,flush=True)
ThreadingHTTPServer(('127.0.0.1',18765),Handler).serve_forever()
