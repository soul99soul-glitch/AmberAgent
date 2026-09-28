#!/usr/bin/env python3
"""Loopback-only, deterministic OpenAI SSE tool-call fixture. No real provider."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import uuid
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or b'{}')
        model=request.get('model','gpt-4o')
        if not request.get('stream'):
            self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
            self.wfile.write(json.dumps({'id':'e2-title','object':'chat.completion','model':model,
                'choices':[{'index':0,'message':{'role':'assistant','content':'E2 SSH approval'},'finish_reason':'stop'}]}).encode());return
        messages=request.get('messages',[])
        last_user=max((i for i,m in enumerate(messages) if m.get('role')=='user'), default=-1)
        completed=any(m.get('role')=='tool' for m in messages[last_user+1:])
        delta={'role':'assistant','content':'Remote SSH tool returned; inspect its actual result.'} if completed else {
            'role':'assistant','tool_calls':[{'index':0,'id':'e2-ssh-'+uuid.uuid4().hex,'type':'function',
                'function':{'name':'terminal_execute','arguments':json.dumps({'profile_id':'',
                    'command':"printf 'E2 agent approved\\n'"})}}]}
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        chunk={'id':'e2-ssh','object':'chat.completion.chunk','created':1,'model':model,
            'choices':[{'index':0,'delta':delta,'finish_reason':None}]}
        ending={'id':'e2-ssh','object':'chat.completion.chunk','created':1,'model':model,
            'choices':[{'index':0,'delta':{},'finish_reason':'stop' if completed else 'tool_calls'}]}
        for data in (chunk,ending):self.wfile.write(('data: '+json.dumps(data)+'\n\n').encode())
        self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()
    def log_message(self,*_args):pass
HTTPServer(('127.0.0.1',8765),Handler).serve_forever()
