const transcript = document.querySelector("#transcript");
const steps = document.querySelector("#steps");
const devices = document.querySelector("#devices");
const llmStatus = document.querySelector("#llm-status");
const form = document.querySelector("#composer");
const input = document.querySelector("#input");
const sendButton = document.querySelector("#send");
const state = { messages: [], busy: false };

document.querySelectorAll(".suggestions button").forEach((button) => {
  button.addEventListener("click", () => {
    input.value = button.dataset.text;
    input.focus();
  });
});

form.addEventListener("submit", (event) => {
  event.preventDefault();
  submit(input.value);
});

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    submit(input.value);
  }
});

document.querySelector("#clear-chat").addEventListener("click", () => {
  if (state.busy) return;
  state.messages = [];
  renderTranscript();
  renderSteps([]);
});

document.querySelector("#reset-devices").addEventListener("click", async () => {
  const response = await fetch("/api/devices/reset", { method: "POST" });
  renderDevices(await response.json());
});

loadLlm();
loadDevices();

async function loadLlm() {
  const info = await fetch("/api/llm").then((response) => response.json());
  llmStatus.textContent = info.configured
    ? `已配置 ${info.model} · ${info.baseUrl}`
    : `未配置密钥。当前准备连接 ${info.model} · ${info.baseUrl}`;
}

async function loadDevices() {
  renderDevices(await fetch("/api/devices").then((response) => response.json()));
}

async function submit(raw) {
  const text = raw.trim();
  if (!text || state.busy) return;
  state.busy = true;
  sendButton.disabled = true;
  input.value = "";
  state.messages.push({ role: "user", content: text });
  renderTranscript();
  renderSteps([{ kind: "mcp", title: "等待服务响应", detail: "正在把这句话交给模型和 MCP。" }]);
  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
      body: JSON.stringify({ messages: state.messages })
    });
    if (!response.ok || !response.body) {
      throw new Error("对话接口没有返回事件流");
    }
    const liveSteps = [];
    renderSteps(liveSteps);
    await readEvents(response, (eventName, data) => {
      if (eventName === "step") {
        liveSteps.push(data);
        renderSteps(liveSteps);
      } else if (eventName === "done") {
        state.messages.push({ role: "assistant", content: data.reply });
        renderTranscript();
        renderDevices(data.devices);
      } else if (eventName === "error") {
        state.messages.push({ role: "assistant", content: data.message });
        renderTranscript();
        liveSteps.push({ kind: "error", title: "失败", detail: data.message });
        renderSteps(liveSteps);
      }
    });
  } catch (error) {
    state.messages.push({ role: "assistant", content: error.message });
    renderTranscript();
  } finally {
    state.busy = false;
    sendButton.disabled = false;
    input.focus();
  }
}

async function readEvents(response, onEvent) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    buffer += decoder.decode(chunk.value, { stream: true });
    buffer = drain(buffer, onEvent);
  }
  buffer += decoder.decode();
  if (buffer.trim()) drain(buffer + "\n\n", onEvent);
}

function drain(buffer, onEvent) {
  const blocks = buffer.split(/\n\n/);
  const rest = blocks.pop();
  for (const block of blocks) {
    if (!block.trim()) continue;
    let eventName = "message";
    const dataLines = [];
    for (const line of block.split(/\n/)) {
      if (line.startsWith("event:")) eventName = line.slice(6).trim();
      if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
    }
    if (dataLines.length === 0) continue;
    onEvent(eventName, JSON.parse(dataLines.join("\n")));
  }
  return rest;
}

function renderTranscript() {
  transcript.replaceChildren();
  if (state.messages.length === 0) {
    const hint = document.createElement("p");
    hint.className = "hint";
    hint.textContent = "试着说「把空调温度降低一度」。客厅和卧室各有一台在线空调，书房那台是离线的。没指明是哪一台时，助手会先问你。";
    transcript.append(hint);
    return;
  }
  for (const message of state.messages) {
    const bubble = document.createElement("p");
    bubble.className = `bubble ${message.role}`;
    bubble.textContent = message.content;
    transcript.append(bubble);
  }
  transcript.scrollTop = transcript.scrollHeight;
}

function renderSteps(items) {
  steps.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "发送一句话后，这里会按顺序显示连接 MCP、模型决定和工具结果。";
    steps.append(empty);
    return;
  }
  for (const item of items) {
    const row = document.createElement("li");
    row.className = item.kind || "mcp";
    const title = document.createElement("strong");
    const kind = document.createElement("small");
    const detail = document.createElement("p");
    kind.textContent = label(item.kind);
    title.textContent = item.title || "";
    detail.textContent = item.detail || "";
    row.append(kind, title);
    if (item.detail) row.append(detail);
    steps.append(row);
  }
  steps.scrollTop = steps.scrollHeight;
}

function renderDevices(items) {
  devices.replaceChildren();
  for (const device of items) {
    const card = document.createElement("article");
    card.className = device.online ? "device" : "device offline";
    const header = document.createElement("header");
    const name = document.createElement("strong");
    const place = document.createElement("span");
    name.textContent = device.name;
    place.textContent = `${device.location} · ${device.id}`;
    header.append(name, place);
    const setpoint = document.createElement("p");
    setpoint.className = "setpoint";
    setpoint.textContent = String(device.setpointCelsius);
    const unit = document.createElement("span");
    unit.textContent = "℃";
    setpoint.append(unit);
    const meta = document.createElement("p");
    meta.className = "meta";
    const temp = document.createElement("span");
    const presence = document.createElement("span");
    temp.textContent = `室温 ${device.currentCelsius}℃`;
    presence.textContent = device.online ? "在线" : "离线";
    meta.append(temp, presence);
    card.append(header, setpoint, meta);
    devices.append(card);
  }
}

function label(kind) {
  if (kind === "llm") return "模型";
  if (kind === "error") return "失败";
  return "MCP";
}
