#!/usr/bin/env python3
"""Mock MCP server + OAuth authorization server + REST API + OpenAI-style chat endpoint for Switchboard's unit tests."""
import json, sys, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])
BASE = f"http://127.0.0.1:{PORT}"
STATS = {"refreshes": 0, "token_calls": 0, "chat_calls": 0}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, obj=None, headers=None, raw=None, ctype="application/json"):
        body = raw if raw is not None else (json.dumps(obj).encode() if obj is not None else b"")
        self.send_response(code)
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)
    def body(self):
        n = int(self.headers.get("Content-Length") or 0); return self.rfile.read(n)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/.well-known/oauth-protected-resource/mcp":
            return self.send(200, {"resource": BASE + "/mcp", "authorization_servers": [BASE + "/auth"]})
        if u.path == "/.well-known/oauth-authorization-server/auth":
            return self.send(200, {"issuer": BASE + "/auth", "authorization_endpoint": BASE + "/auth/authorize",
                                   "token_endpoint": BASE + "/auth/token", "registration_endpoint": BASE + "/auth/register"})
        if u.path == "/api/items":
            if self.headers.get("X-API-Key") != "k": return self.send(401, {"error": "bad key"})
            return self.send(200, {"items": ["a", "b"], "q": urllib.parse.parse_qs(u.query).get("q", [""])[0]})
        if u.path == "/stats": return self.send(200, STATS)
        self.send(404, {"error": "nope"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        raw = self.body()
        if u.path == "/auth/register":
            req = json.loads(raw); assert "switchboard://oauth" in req["redirect_uris"]
            return self.send(201, {"client_id": "dyn-client"})
        if u.path == "/auth/token":
            STATS["token_calls"] += 1
            f = {k: v[0] for k, v in urllib.parse.parse_qs(raw.decode()).items()}
            if f.get("grant_type") == "authorization_code" and f.get("code") == "abc" and f.get("client_id") == "dyn-client" \
                    and f.get("code_verifier") and f.get("resource") == BASE + "/mcp":
                return self.send(200, {"access_token": "good-access", "refresh_token": "r1", "expires_in": 1, "token_type": "Bearer"})
            if f.get("grant_type") == "refresh_token" and f.get("refresh_token") == "r1":
                STATS["refreshes"] += 1
                return self.send(200, {"access_token": "good-access", "expires_in": 3600, "token_type": "Bearer"})
            return self.send(400, {"error": "invalid_grant", "error_description": json.dumps(f)})
        if u.path == "/mcp":
            if self.headers.get("Authorization") != "Bearer good-access": return self.send(401, {"error": "unauthorized"})
            msg = json.loads(raw)
            if msg["method"] == "initialize":
                return self.send(200, {"jsonrpc": "2.0", "id": msg["id"], "result": {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                                       "serverInfo": {"name": "mock", "version": "1"}}}, headers={"Mcp-Session-Id": "s1"})
            if self.headers.get("Mcp-Session-Id") != "s1": return self.send(400, {"error": "no session"})
            if msg["method"] == "notifications/initialized": return self.send(202)
            if msg["method"] == "tools/list":  # answer over SSE, with a notification first
                events = [{"jsonrpc": "2.0", "method": "notifications/message", "params": {"level": "info", "data": "hi"}},
                          {"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [{"name": "echo", "description": "Echo text back",
                           "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}}]}}]
                sse = "".join(f"event: message\ndata: {json.dumps(e)}\n\n" for e in events).encode()
                return self.send(200, raw=sse, ctype="text/event-stream")
            if msg["method"] == "tools/call":
                text = msg["params"]["arguments"].get("text", "")
                return self.send(200, {"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "echo: " + text}], "isError": False}})
            return self.send(200, {"jsonrpc": "2.0", "id": msg.get("id"), "error": {"code": -32601, "message": "unknown method"}})
        if u.path == "/v1/chat/completions":  # one tool call, then a final answer once the tool result is in the history
            STATS["chat_calls"] += 1
            req = json.loads(raw)
            if any(m["role"] == "tool" for m in req["messages"]):
                result = [m for m in req["messages"] if m["role"] == "tool"][-1]["content"]
                chunks = [{"choices": [{"delta": {"content": "Done: " + result}, "finish_reason": "stop"}]}]
            else:
                chunks = [{"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "c1", "type": "function",
                           "function": {"name": "mcp__test__echo", "arguments": "{\"text\":\"ping\"}"}}]}, "finish_reason": "tool_calls"}]}]
            sse = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n"
            return self.send(200, raw=sse.encode(), ctype="text/event-stream")
        self.send(404, {"error": "nope"})

srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
print("ready", flush=True)
srv.serve_forever()
