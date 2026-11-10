/* ============================================================
   DocumentGPT — Chat Frontend
   Features:
   1. Streaming responses (with Stop Response capability)
   2. Markdown + DOMPurify (XSS safe)
   3. Timestamps on messages
   4. Document context sidebar
   5. Toast notifications
   6. Light/Dark mode
   7. Glassmorphism styling
   8. Mobile responsive with drawer
   9. Per-document chat persistence with Previous Conversations
   ============================================================ */

// ── Marked.js Configuration ──────────────────────────────────────────────
if (typeof marked !== "undefined") {
  marked.setOptions({
    breaks: true,
    gfm: true
  });
}

// ── DOM References ────────────────────────────────────────────────────────
const chatInput = document.getElementById("chat-input");
const sendBtn = document.getElementById("send-btn");
const stopGeneratingBtn = document.getElementById("stop-generating-btn");
const chatContainer = document.getElementById("chat-container");
const deleteBtn = document.getElementById("delete-btn");
const deleteDocumentBtn = document.getElementById("delete-document-btn");
const themeToggle = document.getElementById("theme-toggle");
const scrollBottomBtn = document.getElementById("scroll-bottom-btn");
const sidebar = document.getElementById("sidebar");
const appWrapper = document.getElementById("app-wrapper");
const sidebarToggleMob = document.getElementById("sidebar-toggle-mobile");
const sidebarCollapseBtn = document.getElementById("sidebar-collapse-btn");
const sidebarToggleDesk = document.getElementById("sidebar-toggle-desktop");
const toastContainer = document.getElementById("toast-container");

// ── CSRF Protection ─────────────────────────────────────
function getCsrfHeaders() {
  const token = document.querySelector('meta[name="_csrf"]')?.content;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content;

  return token && header ? { [header]: token } : {};
}

// ── State ─────────────────────────────────────────────────────────────────
let userText = null;
let isStreaming = false;
let streamBuffer = "";
let initialInputHeight = 55;

// ── WebSocket Setup ───────────────────────────────────────────────────────
const wsUrl = new URL("/ws-chat", location.href);
wsUrl.protocol = wsUrl.protocol.replace("http", "ws");
const ws = new WebSocket(wsUrl.toString());

ws.onopen = () => showToast("Connected to DocumentGPT", "success");
ws.onclose = () => {
  setStreamingState(false);
  showToast("Connection closed. Please refresh.", "warning");
};
ws.onerror = () => {
  setStreamingState(false);
  showToast("WebSocket error. Please refresh.", "error");
};

ws.onmessage = function (event) {
  const data = event.data;

  if (data === "[DONE]") {
    finalizeStream();
    return;
  }

  if (data.startsWith("[ERROR]:")) {
    const msg = data.slice(8) || "An error occurred.";
    insertErrorIntoStream(msg);
    finalizeStream();
    return;
  }

  // Accumulate token
  streamBuffer += data;
  updateStreamBubble(streamBuffer);
};

// ── Streaming Helpers ─────────────────────────────────────────────────────
function setStreamingState(streaming) {
  isStreaming = streaming;
  if (streaming) {
    stopGeneratingBtn?.classList.remove("hidden");
    if (sendBtn) {
      sendBtn.classList.add("stop-mode");
      sendBtn.title = "Stop response";
      const icon = sendBtn.querySelector(".material-symbols-rounded");
      if (icon) icon.textContent = "stop";
    }
  } else {
    stopGeneratingBtn?.classList.add("hidden");
    if (sendBtn) {
      sendBtn.classList.remove("stop-mode");
      sendBtn.title = "Send";
      const icon = sendBtn.querySelector(".material-symbols-rounded");
      if (icon) icon.textContent = "send";
    }
    streamBuffer = "";
  }
}

function stopStreaming() {
  if (!isStreaming) return;

  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send("[STOP]");
  }

  const bubble = document.getElementById("current-response");
  if (bubble) {
    bubble.querySelector(".typing-animation")?.remove();
    let contentEl = bubble.querySelector(".ai-message-content");
    if (!contentEl) {
      contentEl = document.createElement("div");
      contentEl.className = "ai-message-content";
      bubble.querySelector(".message-wrapper")?.prepend(contentEl);
    }
    if (streamBuffer) {
      contentEl.innerHTML = renderMarkdown(streamBuffer);
    }
    const stoppedTag = document.createElement("span");
    stoppedTag.className = "stopped-tag";
    stoppedTag.textContent = " (response stopped)";
    contentEl.appendChild(stoppedTag);
    bubble.removeAttribute("id");
  }

  setStreamingState(false);
  saveChatToStorage();
  scrollToBottom("smooth");
  checkScrollBtn();
  showToast("Response stopped.", "info");
}

function updateStreamBubble(text) {
  const bubble = document.getElementById("current-response");
  if (!bubble) return;

  // Remove typing animation once first token arrives
  bubble.querySelector(".typing-animation")?.remove();

  let contentEl = bubble.querySelector(".ai-message-content");
  if (!contentEl) {
    contentEl = document.createElement("div");
    contentEl.className = "ai-message-content";
    bubble.querySelector(".message-wrapper").prepend(contentEl);
  }

  contentEl.innerHTML = renderMarkdown(text);
}

function insertErrorIntoStream(msg) {
  const bubble = document.getElementById("current-response");
  if (!bubble) return;
  bubble.querySelector(".typing-animation")?.remove();
  let contentEl = bubble.querySelector(".ai-message-content");
  if (!contentEl) {
    contentEl = document.createElement("div");
    contentEl.className = "ai-message-content";
    bubble.querySelector(".message-wrapper").prepend(contentEl);
  }
  const errP = document.createElement("p");
  errP.className = "error";
  errP.textContent = msg;
  contentEl.appendChild(errP);
}

function finalizeStream() {
  const bubble = document.getElementById("current-response");
  if (bubble) {
    // Final markdown render
    const contentEl = bubble.querySelector(".ai-message-content");
    if (contentEl && streamBuffer) {
      contentEl.innerHTML = renderMarkdown(streamBuffer);
    }
    bubble.removeAttribute("id");
  }
  setStreamingState(false);
  saveChatToStorage();
  scrollToBottom("smooth");
  checkScrollBtn();
}

function renderMarkdown(text) {
  if (typeof marked !== "undefined" && typeof DOMPurify !== "undefined") {
    return DOMPurify.sanitize(marked.parse(text));
  }
  // Fallback: plain text
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

// ── Toast System ──────────────────────────────────────────────────────────
function showToast(message, type = "info") {
  if (!toastContainer) return;
  const icons = { success: "check_circle", error: "error", warning: "warning", info: "info" };
  const toast = document.createElement("div");
  toast.className = `toast toast-${type}`;
  toast.innerHTML = `<span class="material-symbols-rounded toast-icon">${icons[type] || "info"}</span><span>${message}</span>`;
  toastContainer.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add("show"));
  setTimeout(() => {
    toast.classList.remove("show");
    setTimeout(() => toast.remove(), 350);
  }, 3800);
}

// ── Theme ─────────────────────────────────────────────────────────────────
function initTheme() {
  if (localStorage.getItem("theme") === "light") document.body.classList.add("light-mode");
  syncThemeIcon();
}

function toggleTheme() {
  document.body.classList.toggle("light-mode");
  localStorage.setItem("theme", document.body.classList.contains("light-mode") ? "light" : "dark");
  syncThemeIcon();
}

function syncThemeIcon() {
  if (!themeToggle) return;
  themeToggle.querySelector(".material-symbols-rounded").textContent =
    document.body.classList.contains("light-mode") ? "dark_mode" : "light_mode";
}

// ── Sidebar ───────────────────────────────────────────────────────────────
function initSidebar() {
  const info = JSON.parse(sessionStorage.getItem("documentInfo") || "null");
  const emptyCard = document.getElementById("doc-card-empty");
  const loadedCard = document.getElementById("doc-card-loaded");

  if (info && loadedCard) {
    if (emptyCard) emptyCard.style.display = "none";
    loadedCard.style.display = "flex";
    const docNameEl = document.getElementById("doc-name");
    const docSizeEl = document.getElementById("doc-size");
    const docTimeEl = document.getElementById("doc-upload-time");
    if (docNameEl) docNameEl.textContent = info.filename || "document.pdf";
    if (docSizeEl) docSizeEl.textContent = info.size ? formatBytes(parseInt(info.size)) : "—";
    if (docTimeEl) docTimeEl.textContent = info.uploadTime || "—";
  }
}

// ── Previous Conversations ────────────────────────────────────────────────

async function renderPreviousConversations() {
  const container = document.getElementById("prev-conversations");
  if (!container) return;

  container.innerHTML = '<p class="no-conv">Loading conversations...</p>';

  try {
    const response = await fetch("/conversations");

    if (!response.ok) {
      throw new Error("Failed to load conversations.");
    }

    const conversations = await response.json();
    container.innerHTML = "";

    if (conversations.length === 0) {
      container.innerHTML = '<p class="no-conv">No previous chats</p>';
      return;
    }

    conversations.forEach(conversation => {
      const itemDiv = document.createElement("div");
      itemDiv.className = "prev-conv-item";

      const link = document.createElement("button");
      link.className = "prev-conv-link";
      link.textContent = conversation.title;
      link.title = "Load conversation";

      link.addEventListener("click", () => {
        loadConversation(conversation);
      });

      itemDiv.appendChild(link);

      const del = document.createElement("span");
      del.className = "delete-icon material-symbols-rounded";
      del.textContent = "close";
      del.title = "Delete conversation";

      del.addEventListener("click", async e => {
        e.stopPropagation();

        if (!confirm("Delete this conversation? This cannot be undone.")) {
          return;
        }

        try {
          const result = await fetch(
            `/conversations/${encodeURIComponent(conversation.conversationId)}`,
            {
              method: "DELETE",
              headers: getCsrfHeaders()
            }
          );

          if (!result.ok) {
            throw new Error("Failed to delete conversation.");
          }

          const activeInfo = JSON.parse(
            sessionStorage.getItem("documentInfo") || "null"
          );

          if (activeInfo?.conversationId === conversation.conversationId) {
            sessionStorage.removeItem("documentInfo");
            loadChatFromStorage();
            initSidebar();
          }

          renderPreviousConversations();
          showToast("Conversation deleted.", "success");

        } catch (error) {
          showToast(error.message, "error");
        }
      });

      itemDiv.appendChild(del);
      container.appendChild(itemDiv);
    });

  } catch (error) {
    container.innerHTML = '<p class="no-conv">Unable to load conversations</p>';
    showToast(error.message, "error");
  }
}


async function loadConversation(conversation) {
  try {
    const response = await fetch(
      `/conversations/${encodeURIComponent(conversation.conversationId)}/messages`
    );

    if (!response.ok) {
      throw new Error("Failed to load conversation messages.");
    }

    const messages = await response.json();

    sessionStorage.setItem("documentInfo", JSON.stringify({
      documentId: conversation.documentId,
      conversationId: conversation.conversationId,
      filename: conversation.title,
      size: 0,
      uploadTime: ""
    }));

    initSidebar();

    chatContainer.innerHTML = "";

    messages.forEach(message => {
      const chatDiv = document.createElement("div");
      chatDiv.className = message.role === "user"
        ? "chat outgoing"
        : "chat incoming";

      const content = document.createElement("div");
      content.className = "chat-content";

      const details = document.createElement("div");
      details.className = "chat-details";

      const img = document.createElement("img");
      img.src = message.role === "user"
        ? "/images/person.png"
        : "/images/spring-ai-logo.png";
      img.alt = message.role === "user" ? "You" : "DocumentGPT";

      const wrapper = document.createElement("div");
      wrapper.className = "message-wrapper";

      const messageContent = document.createElement("div");

      if (message.role === "user") {
        const p = document.createElement("p");
        p.textContent = message.content;
        wrapper.appendChild(p);
      } else {
        messageContent.className = "ai-message-content";
        messageContent.innerHTML = renderMarkdown(message.content);
        wrapper.appendChild(messageContent);
      }

      const timestamp = document.createElement("span");
      timestamp.className = "timestamp";
      timestamp.textContent = message.createdAt
        ? new Date(message.createdAt).toLocaleTimeString([], {
          hour: "2-digit",
          minute: "2-digit"
        })
        : "";

      wrapper.appendChild(timestamp);
      details.appendChild(img);
      details.appendChild(wrapper);
      content.appendChild(details);
      chatDiv.appendChild(content);

      if (message.role === "assistant") {
        const copyBtn = document.createElement("button");
        copyBtn.className = "copy-btn icon-btn";
        copyBtn.title = "Copy response";
        copyBtn.innerHTML =
          '<span class="material-symbols-rounded">content_copy</span>';
        copyBtn.addEventListener("click", () => copyResponse(copyBtn));
        content.appendChild(copyBtn);
      }

      chatContainer.appendChild(chatDiv);
    });

    scrollToBottom("instant");
    checkScrollBtn();

  } catch (error) {
    showToast(error.message, "error");
  }
}


function formatBytes(b) {
  if (b < 1024) return b + " B";
  if (b < 1048576) return (b / 1024).toFixed(1) + " KB";
  return (b / 1048576).toFixed(1) + " MB";
}

function toggleSidebarCollapse() {
  sidebar?.classList.toggle("collapsed");
}

function toggleSidebarMobile() {
  appWrapper?.classList.toggle("sidebar-open");
}

// ── Chat Persistence ──────────────────────────────────────────────────────
function getChatStorageKey() {
  return null;
}

function saveChatToStorage() {
  // Chat messages are now saved by the backend.
}

function loadChatFromStorage() {
  const defaultHTML = `
    <div class="default-text">
      <span class="material-symbols-rounded default-icon">forum</span>
      <h1>DocumentGPT</h1>
      <p>Upload a document and start asking questions.<br>Your conversation will appear here.</p>
    </div>`;

  if (!chatContainer) return;

  chatContainer.innerHTML = defaultHTML;
  scrollToBottom("instant");
  checkScrollBtn();
}

// ── Timestamp ─────────────────────────────────────────────────────────────
function getTimestamp() {
  return new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

// ── Outgoing Message ──────────────────────────────────────────────────────
function handleOutgoingChat() {
  // If clicked while streaming, act as Stop button
  if (isStreaming) {
    stopStreaming();
    return;
  }

  const text = chatInput?.value.trim();
  if (!text) return;

  if (ws.readyState !== WebSocket.OPEN) {
    showToast("Not connected. Please refresh the page.", "error");
    return;
  }

  userText = text;
  chatInput.value = "";
  if (chatInput) {
    chatInput.style.height = `${initialInputHeight}px`;
  }

  // Build outgoing bubble safely (no innerHTML with user content → no XSS)
  const msgWrap = document.createElement("div");
  msgWrap.className = "message-wrapper";

  const textP = document.createElement("p");
  textP.textContent = userText;

  const tsSpan = document.createElement("span");
  tsSpan.className = "timestamp";
  tsSpan.textContent = getTimestamp();

  msgWrap.appendChild(textP);
  msgWrap.appendChild(tsSpan);

  const details = document.createElement("div");
  details.className = "chat-details";
  const img = document.createElement("img");
  img.src = "/images/person.png";
  img.alt = "You";
  details.appendChild(img);
  details.appendChild(msgWrap);

  const content = document.createElement("div");
  content.className = "chat-content";
  content.appendChild(details);

  const chatDiv = document.createElement("div");
  chatDiv.className = "chat outgoing";
  chatDiv.appendChild(content);

  chatContainer?.querySelector(".default-text")?.remove();
  chatContainer?.appendChild(chatDiv);
  scrollToBottom("smooth");

  setStreamingState(true);
  streamBuffer = "";
  setTimeout(showTypingAnimation, 400);
}

// ── Typing Animation + Send ───────────────────────────────────────────────
function showTypingAnimation() {
  const ts = getTimestamp();
  const html = `
    <div class="chat-content" id="current-response">
      <div class="chat-details">
        <img src="/images/spring-ai-logo.png" alt="DocumentGPT">
        <div class="message-wrapper">
          <div class="typing-animation">
            <div class="typing-dot" style="--delay:0.2s"></div>
            <div class="typing-dot" style="--delay:0.3s"></div>
            <div class="typing-dot" style="--delay:0.4s"></div>
          </div>
          <span class="timestamp">${ts}</span>
        </div>
      </div>
      <button class="copy-btn icon-btn" onclick="copyResponse(this)" title="Copy response">
        <span class="material-symbols-rounded">content_copy</span>
      </button>
    </div>`;

  const incomingDiv = document.createElement("div");
  incomingDiv.className = "chat incoming";
  incomingDiv.innerHTML = html;
  chatContainer?.appendChild(incomingDiv);
  scrollToBottom("smooth");

  try {
    const info = JSON.parse(sessionStorage.getItem("documentInfo") || "null");

    if (!info?.documentId) {
      showToast("No active document found. Please upload a PDF.", "error");
      setStreamingState(false);
      return;
    }

    ws.send(JSON.stringify({
      question: userText,
      documentId: info.documentId,
      conversationId: info.conversationId
    }));
  } catch (err) {
    showToast("Failed to send message. Please try again.", "error");
    setStreamingState(false);
    document.getElementById("current-response")?.remove();
  }
}

// ── Copy Response ─────────────────────────────────────────────────────────
function copyResponse(btn) {
  const content = btn.closest(".chat-content")?.querySelector(".ai-message-content, p");
  if (!content) return;
  navigator.clipboard.writeText(content.textContent).then(() => {
    btn.querySelector(".material-symbols-rounded").textContent = "done";
    setTimeout(() => btn.querySelector(".material-symbols-rounded").textContent = "content_copy", 1500);
  });
}

// ── Scroll Helpers ────────────────────────────────────────────────────────
function scrollToBottom(behavior = "smooth") {
  chatContainer?.scrollTo({ top: chatContainer.scrollHeight, behavior });
}

function checkScrollBtn() {
  if (!scrollBottomBtn || !chatContainer) return;
  const atBottom = chatContainer.scrollTop + chatContainer.clientHeight >= chatContainer.scrollHeight - 60;
  scrollBottomBtn.classList.toggle("hidden", atBottom);
}

// ── Delete Current Chat ───────────────────────────────────────────────────
async function deleteChat() {
  const info = JSON.parse(
    sessionStorage.getItem("documentInfo") || "null"
  );

  if (!info?.conversationId) {
    showToast("No active conversation found.", "error");
    return;
  }

  if (!confirm("Delete this conversation? This cannot be undone.")) {
    return;
  }

  try {
    const response = await fetch(
      `/conversations/${encodeURIComponent(info.conversationId)}`,
      {
        method: "DELETE",
        headers: getCsrfHeaders()
      }
    );

    if (!response.ok) {
      throw new Error("Failed to delete conversation.");
    }

    sessionStorage.removeItem("documentInfo");
    loadChatFromStorage();
    initSidebar();
    renderPreviousConversations();

    showToast("Conversation deleted.", "success");

  } catch (error) {
    showToast(error.message, "error");
  }
}
async function deleteDocument() {
  const info = JSON.parse(sessionStorage.getItem("documentInfo") || "null");

  if (!info?.documentId) {
    showToast("No active document found.", "error");
    return;
  }

  if (!confirm("Permanently delete this document and all its stored data?")) {
    return;
  }

  try {
    const response = await fetch(`/documents/${encodeURIComponent(info.documentId)}`, {
      method: "DELETE",
      headers: getCsrfHeaders()
    });

    const data = await response.json();

    if (!response.ok || !data.success) {
      throw new Error(data.error || "Document deletion failed.");
    }


    sessionStorage.removeItem("documentInfo");
    sessionStorage.removeItem("activeChatKey");

    loadChatFromStorage();
    initSidebar();
    renderPreviousConversations();

    showToast("Document and its stored vectors deleted successfully.", "success");

  } catch (error) {
    showToast(error.message || "Failed to delete document.", "error");
  }
}

// ── Event Listeners ───────────────────────────────────────────────────────
if (chatInput) {
  initialInputHeight = chatInput.scrollHeight || 55;

  chatInput.addEventListener("input", () => {
    chatInput.style.height = `${initialInputHeight}px`;
    chatInput.style.height = `${chatInput.scrollHeight}px`;
  });

  chatInput.addEventListener("keydown", e => {
    if (e.key === "Enter" && !e.shiftKey && window.innerWidth > 600) {
      e.preventDefault();
      handleOutgoingChat();
    }
  });
}

// Stop generation via button, send button or Escape key
stopGeneratingBtn?.addEventListener("click", stopStreaming);
document.addEventListener("keydown", e => {
  if (e.key === "Escape" && isStreaming) {
    stopStreaming();
  }
});

sendBtn?.addEventListener("click", handleOutgoingChat);
deleteBtn?.addEventListener("click", deleteChat);
deleteDocumentBtn?.addEventListener("click", deleteDocument);
themeToggle?.addEventListener("click", toggleTheme);

scrollBottomBtn?.addEventListener("click", () => scrollToBottom("smooth"));
chatContainer?.addEventListener("scroll", checkScrollBtn);

sidebarCollapseBtn?.addEventListener("click", toggleSidebarCollapse);
sidebarToggleDesk?.addEventListener("click", toggleSidebarCollapse);
sidebarToggleMob?.addEventListener("click", toggleSidebarMobile);

// Close mobile sidebar when clicking outside
document.addEventListener("click", e => {
  if (window.innerWidth <= 768 &&
    appWrapper?.classList.contains("sidebar-open") &&
    !sidebar?.contains(e.target) &&
    !sidebarToggleMob?.contains(e.target)) {
    appWrapper.classList.remove("sidebar-open");
  }
});

// ── Initialise ────────────────────────────────────────────────────────────
initTheme();
loadChatFromStorage();
initSidebar();
renderPreviousConversations();
