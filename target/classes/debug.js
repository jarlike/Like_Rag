const el = (id) => document.getElementById(id);

const statusText = {
    UPLOADED: "待索引",
    INDEXING: "索引中",
    INDEXED: "已索引",
    FAILED: "失败"
};

const storageKey = "like-rag-api-base";
let apiBase = localStorage.getItem(storageKey) || "";

let chatMessages = [];
let isSending = false;

async function request(url, options = {}) {
    const target = apiBase ? `${apiBase.replace(/\/$/, "")}${url}` : url;
    const response = await fetch(target, options);
    const contentType = response.headers.get("content-type") || "";
    if (!response.ok) {
        let message = response.statusText;
        try {
            const body = contentType.includes("application/json") ? await response.json() : null;
            message = body.message || message;
        } catch (ignored) {
        }
        throw new Error(message);
    }
    if (response.status === 204) {
        return null;
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function fmtTime(value) {
    return value ? String(value).replace("T", " ").slice(0, 19) : "-";
}

function fmtScore(value) {
    return Number(value || 0).toFixed(4);
}

function fmtSize(size) {
    const value = Number(size || 0);
    if (value >= 1024 * 1024) {
        return `${(value / 1024 / 1024).toFixed(2)} MB`;
    }
    if (value >= 1024) {
        return `${(value / 1024).toFixed(1)} KB`;
    }
    return `${value} B`;
}

function labelStatus(status) {
    return statusText[status] || status || "-";
}

function emptyState(text) {
    return `<div class="empty-state">${escapeHtml(text)}</div>`;
}

function setApiState(kind, text) {
    const state = el("apiState");
    state.className = `api-state ${kind || ""}`.trim();
    state.textContent = text;
}

function updateApiBaseInput() {
    const input = el("apiBaseInput");
    input.value = apiBase || `${window.location.protocol}//${window.location.host}`;
}

function apiBaseForFetch() {
    return apiBase ? apiBase.replace(/\/$/, "") : "";
}

async function probeApi() {
    try {
        await fetch(`${apiBaseForFetch()}/api/documents`);
        setApiState("ok", apiBase ? "已连接到后端" : "当前页面后端可用");
    } catch (error) {
        setApiState("error", `后端不可用：${error.message}`);
    }
}

function renderChatThread() {
    const thread = el("chatThread");
    if (!chatMessages.length) {
        thread.innerHTML = emptyState("先上传并索引文档，然后在这里和 GPT-5.5 对话。");
        return;
    }
    thread.innerHTML = chatMessages.map(renderChatMessage).join("");
    thread.scrollTop = thread.scrollHeight;
}

function renderChatMessage(message) {
    const role = message.role || "assistant";
    const roleLabel = role === "user" ? "你" : role === "system" ? "系统" : "GPT-5.5";
    const roleTitle = role === "user" ? "用户" : role === "system" ? "系统" : "助手";
    const state = message.status || "";
    const timestamp = fmtTime(message.createdAt);
    const citationCount = Array.isArray(message.citations)
        ? message.citations.length
        : Number(message.citationCount || 0);
    const pills = [];
    if (timestamp !== "-") {
        pills.push(`<span class="message-pill">${escapeHtml(timestamp)}</span>`);
    }
    if (role === "assistant" && citationCount > 0) {
        pills.push(`<span class="message-pill">${citationCount} 条引用</span>`);
    }
    if (state === "pending") {
        pills.push(`<span class="message-pill pending">生成中</span>`);
    }
    if (state === "error") {
        pills.push(`<span class="message-pill error">失败</span>`);
    }
    return `
        <article class="chat-message ${escapeHtml(role)} ${escapeHtml(state)}">
            <div class="message-avatar">${escapeHtml(roleLabel)}</div>
            <div class="message-card">
                <div class="message-meta">
                    <span class="message-role">${escapeHtml(roleTitle)}</span>
                    ${pills.join("")}
                </div>
                <div class="message-body">${escapeHtml(message.content || "")}</div>
            </div>
        </article>
    `;
}

function renderCitations(citations) {
    const items = Array.isArray(citations) ? citations : [];
    el("citations").innerHTML = items.map(citation => `
        <article class="item">
            <div class="item-head">
                <div class="item-title">${escapeHtml(citation.documentName)} #${citation.chunkIndex}</div>
                <span class="status">score ${fmtScore(citation.score)}</span>
            </div>
            <div class="meta">${escapeHtml(citation.sectionPath || "未识别章节")}</div>
            <div class="snippet">${escapeHtml(citation.snippet)}</div>
        </article>
    `).join("") || emptyState("本轮回答会显示引用的文档片段。");
}

function setChatBusy(busy) {
    isSending = busy;
    el("askBtn").disabled = busy;
    el("clearBtn").disabled = busy;
    el("topKInput").disabled = busy;
    el("askBtn").textContent = busy ? "生成中..." : "发送";
}

async function uploadDocument(event) {
    event.preventDefault();
    const file = el("fileInput").files[0];
    if (!file) {
        el("uploadState").textContent = "请选择一个文档文件。";
        return;
    }
    const formData = new FormData();
    formData.append("file", file);
    el("uploadState").textContent = "正在上传，上传完成后会自动触发索引。";
    try {
        const doc = await request("/api/documents", {
            method: "POST",
            body: formData
        });
        el("uploadState").textContent = `已上传：${doc.fileName}。索引完成后即可提问。`;
        el("fileInput").value = "";
        await refreshAll();
    } catch (error) {
        el("uploadState").textContent = `上传失败：${error.message}`;
    }
}

function updateOverview(documents) {
    const docs = documents || [];
    const indexed = docs.filter(doc => doc.status === "INDEXED");
    const chunks = docs.reduce((sum, doc) => sum + Number(doc.chunkCount || 0), 0);
    const latest = docs[0];
    el("docCount").textContent = docs.length;
    el("indexedCount").textContent = indexed.length;
    el("chunkCount").textContent = chunks;
    el("latestStatus").textContent = latest ? labelStatus(latest.status) : "待上传";
}

async function loadDocuments() {
    const documents = await request("/api/documents");
    updateOverview(documents);
    el("documents").innerHTML = documents.map(doc => `
        <article class="item">
            <div class="item-head">
                <div>
                    <div class="item-title">${escapeHtml(doc.fileName)}</div>
                    <div class="meta">${escapeHtml(doc.id)}</div>
                </div>
                <span class="status ${escapeHtml(doc.status)}">${escapeHtml(labelStatus(doc.status))}</span>
            </div>
            <div class="meta">切片 ${doc.chunkCount || 0} · ${fmtSize(doc.size)} · 更新 ${fmtTime(doc.updatedAt)}</div>
            ${doc.errorMessage ? `<div class="meta">错误：${escapeHtml(doc.errorMessage)}</div>` : ""}
            <div class="item-actions">
                <button class="secondary" type="button" data-action="reindex" data-document-id="${escapeHtml(doc.id)}">重新索引</button>
                <button class="secondary" type="button" data-action="show-chunks" data-document-id="${escapeHtml(doc.id)}" data-file-name="${escapeHtml(doc.fileName)}">查看切片</button>
            </div>
        </article>
    `).join("") || emptyState("还没有文档，先上传 txt、md、pdf 或 docx 文件。");
}

async function reindex(documentId) {
    await request(`/api/documents/${documentId}/reindex`, {method: "POST"});
    await refreshAll();
}

async function showChunks(documentId, fileName = "") {
    const chunks = await request(`/api/documents/${documentId}/chunks`);
    el("hits").innerHTML = chunks.map(chunk => `
        <article class="item">
            <div class="item-title">${escapeHtml(chunk.documentName)} #${chunk.chunkIndex}</div>
            <div class="meta">${escapeHtml(chunk.sectionPath || "未识别章节")} · tokens ${chunk.tokenCount || 0}</div>
            <div class="snippet">${escapeHtml(chunk.text)}</div>
        </article>
    `).join("") || emptyState(`${fileName || "该文档"} 暂无切片，可能还在索引中。`);
}

async function ask() {
    if (isSending) {
        return;
    }
    const question = el("questionInput").value.trim();
    if (!question) {
        el("questionInput").focus();
        return;
    }
    const rawTopK = Number(el("topKInput").value);
    const topK = Number.isFinite(rawTopK) ? Math.max(1, Math.min(12, rawTopK)) : 5;
    const userMessage = {
        role: "user",
        content: question,
        createdAt: new Date().toISOString()
    };
    const assistantMessage = {
        role: "assistant",
        content: "正在检索文档并调用 GPT-5.5 生成答案...",
        createdAt: new Date().toISOString(),
        status: "pending",
        citations: []
    };
    chatMessages.push(userMessage, assistantMessage);
    el("questionInput").value = "";
    renderChatThread();
    renderCitations([]);
    setChatBusy(true);
    try {
        const response = await request("/api/chat", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({question, topK})
        });
        assistantMessage.status = "done";
        assistantMessage.content = response.answer || "未返回答案。";
        assistantMessage.citations = Array.isArray(response.citations) ? response.citations : [];
        assistantMessage.citationCount = assistantMessage.citations.length;
        renderChatThread();
        renderCitations(assistantMessage.citations);
        renderHits(Array.isArray(response.hits) ? response.hits : []);
        await loadLogs();
    } catch (error) {
        assistantMessage.status = "error";
        assistantMessage.content = `问答失败：${error.message}`;
        renderChatThread();
        renderCitations([]);
    } finally {
        setChatBusy(false);
    }
}

async function search() {
    const q = el("searchInput").value.trim();
    if (!q) {
        el("hits").innerHTML = emptyState("输入关键词或完整问题，可以直接查看 pgvector 召回的片段。");
        return;
    }
    const hits = await request(`/api/search?q=${encodeURIComponent(q)}&topK=${encodeURIComponent(el("topKInput").value || 5)}`);
    renderHits(hits);
}

function renderHits(hits) {
    el("hits").innerHTML = hits.map(hit => `
        <article class="item">
            <div class="item-head">
                <div class="item-title">${escapeHtml(hit.chunk.documentName)} #${hit.chunk.chunkIndex}</div>
                <span class="status">score ${fmtScore(hit.score)}</span>
            </div>
            <div class="meta">${escapeHtml(hit.chunk.sectionPath || "未识别章节")} · tokens ${hit.chunk.tokenCount || 0}</div>
            <div class="snippet">${escapeHtml(hit.chunk.text)}</div>
        </article>
    `).join("") || emptyState("没有命中结果。可以换个关键词，或者确认文档已经索引完成。");
}

async function loadLogs() {
    const logs = await request("/api/logs?limit=80");
    el("logs").innerHTML = logs.map(log => `
        <div class="log">
            <span class="log-type">${escapeHtml(log.type)}</span>
            <span class="muted">${fmtTime(log.createdAt)}</span>
            <div>${escapeHtml(log.message)}</div>
            ${log.documentId ? `<div class="meta">${escapeHtml(log.documentId)}</div>` : ""}
        </div>
    `).join("") || emptyState("暂无日志。上传、索引或提问后这里会出现记录。");
}

function clearQuestion() {
    chatMessages = [];
    el("questionInput").value = "";
    renderChatThread();
    renderCitations([]);
    el("questionInput").focus();
}

function handleDocumentAction(event) {
    const button = event.target.closest("button[data-action]");
    if (!button) {
        return;
    }
    const action = button.dataset.action;
    const documentId = button.dataset.documentId;
    const fileName = button.dataset.fileName || "";
    if (action === "reindex") {
        reindex(documentId);
    }
    if (action === "show-chunks") {
        showChunks(documentId, fileName);
    }
}

async function refreshAll() {
    try {
        await Promise.all([loadDocuments(), loadLogs()]);
    } catch (error) {
        setApiState("error", `刷新失败：${error.message}`);
    }
}

async function connectApi() {
    const input = el("apiBaseInput");
    const nextBase = input.value.trim();
    apiBase = nextBase;
    localStorage.setItem(storageKey, apiBase);
    await probeApi();
    await refreshAll();
}

el("uploadForm").addEventListener("submit", uploadDocument);
el("askBtn").addEventListener("click", ask);
el("clearBtn").addEventListener("click", clearQuestion);
el("searchBtn").addEventListener("click", search);
el("refreshBtn").addEventListener("click", refreshAll);
el("connectBtn").addEventListener("click", connectApi);
el("documents").addEventListener("click", handleDocumentAction);
el("questionInput").addEventListener("keydown", event => {
    if (event.ctrlKey && event.key === "Enter") {
        ask();
    }
});

renderChatThread();
renderCitations([]);
el("hits").innerHTML = emptyState("可以在这里调试召回结果，也可以点文档的“查看切片”。");
updateApiBaseInput();
probeApi().then(refreshAll);
