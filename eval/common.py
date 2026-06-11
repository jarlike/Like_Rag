#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""评测工具共享模块：tokenize(复现 app)、sub2api embedding、docker exec psql、向量字面量。"""
import os
import subprocess
import time

try:
    import requests
except ImportError:
    requests = None

PG_CONTAINER = os.environ.get("PG_CONTAINER", "pgvector-db")
PG_USER = os.environ.get("PG_USER", "rag")
PG_DB = os.environ.get("PG_DB", "rag")
OPENAI_BASE_URL = os.environ.get("OPENAI_BASE_URL", "http://localhost:8080/v1")
EMBED_MODEL = os.environ.get("EMBED_MODEL", "text-embedding-3-small")
EMBED_DIM = int(os.environ.get("EMBED_DIM", "384"))


# ---- tokenize：复现 app EmbeddingService.tokenize（CJK 单字/双字 + ASCII 词/3-gram）----
def _is_cjk(ch):
    o = ord(ch)
    return (0x4E00 <= o <= 0x9FFF) or (0x3400 <= o <= 0x4DBF) \
        or (0x20000 <= o <= 0x2A6DF) or (0xF900 <= o <= 0xFAFF)


def _is_ascii_word(ch):
    return ('a' <= ch <= 'z') or ('0' <= ch <= '9') or ch == '_'


def tokenize(text):
    text = (text or "").lower()
    tokens, buf = [], []

    def flush():
        if buf:
            w = "".join(buf)
            tokens.append(w)
            for i in range(len(w) - 2):
                tokens.append(w[i:i + 3])
            buf.clear()

    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if _is_ascii_word(ch):
            buf.append(ch)
            i += 1
            continue
        flush()
        if ch.isalnum() or _is_cjk(ch):
            tokens.append(ch)
            if i + 1 < n and _is_cjk(ch) and _is_cjk(text[i + 1]):
                tokens.append(ch + text[i + 1])
        i += 1
    flush()
    return tokens


def build_search_text(*parts):
    toks = []
    for p in parts:
        if p:
            toks.extend(tokenize(p))
    return " ".join(toks)


def to_tsquery_expr(query):
    """与 app EmbeddingService.toTsQuery 一致：OR 连接、清洗 tsquery 语法字符。"""
    seen, out = set(), []
    for t in tokenize(query):
        c = "".join(ch for ch in t if ch not in "&|!():*'\\ ")
        if c and c not in seen:
            seen.add(c)
            out.append(c)
    return " | ".join(out)


# ---- embedding via sub2api（OpenAI 兼容）----
def embed_texts(texts, retries=4):
    if requests is None:
        raise SystemExit("ERROR: 需要 requests 库")
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise SystemExit("ERROR: 请先设置环境变量 OPENAI_API_KEY（sub2api 的 key）")
    url = OPENAI_BASE_URL.rstrip("/") + "/embeddings"
    headers = {"Authorization": "Bearer " + key, "Content-Type": "application/json"}
    body = {"model": EMBED_MODEL, "input": texts, "dimensions": EMBED_DIM}
    last = None
    for attempt in range(retries):
        try:
            r = requests.post(url, json=body, headers=headers, timeout=120)
            if r.status_code == 200:
                data = sorted(r.json()["data"], key=lambda d: d["index"])
                return [d["embedding"] for d in data]
            last = "%s: %s" % (r.status_code, r.text[:200])
            if r.status_code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(0.6 * (2 ** attempt))
                continue
            raise SystemExit("embedding 失败 " + last)
        except requests.RequestException as e:
            last = str(e)
            if attempt < retries - 1:
                time.sleep(0.6 * (2 ** attempt))
                continue
            raise SystemExit("embedding 请求异常 " + last)
    raise SystemExit("embedding 失败 " + str(last))


# ---- pgvector via docker exec psql ----
def run_psql(sql, fetch=False):
    args = ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB,
            "-v", "ON_ERROR_STOP=1"]
    if fetch:
        args += ["-tA", "-F", "\t"]
    p = subprocess.run(args, input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("psql error: " + p.stderr.decode("utf-8", "ignore")[:800])
    return p.stdout.decode("utf-8", "ignore")


def vec_literal(v):
    return "[" + ",".join("%.6f" % x for x in v) + "]"


def esc(s):
    return (s or "").replace("'", "''")
