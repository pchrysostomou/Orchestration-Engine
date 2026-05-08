/* ── State ── */
const state = {
  workflows: [],
  runs: [],
  selectedRun: null,
  activeWs: null,
};

/* ── API helpers ── */
const api = {
  get:  (url)         => fetch(url).then(r => r.json()),
  post: (url, body)   => fetch(url, { method: 'POST',   headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) }).then(r => r.json()),
  del:  (url)         => fetch(url, { method: 'DELETE' }),
};

/* ── DOM refs ── */
const workflowList = document.getElementById('workflow-list');
const runList      = document.getElementById('run-list');
const runDetail    = document.getElementById('run-detail');

/* ── Bootstrap ── */
async function init() {
  await loadWorkflows();
  await loadAllRuns();
  setInterval(loadAllRuns, 5000); // poll for new runs every 5s
}

async function loadWorkflows() {
  state.workflows = await api.get('/api/workflows');
  renderWorkflows();
}

async function loadAllRuns() {
  state.runs = await api.get('/api/runs');
  renderRunList(state.runs);
}

/* ── Render ── */
function renderWorkflows() {
  workflowList.innerHTML = '';
  state.workflows.forEach(wf => {
    const card = el('div', 'workflow-card');
    card.innerHTML = `
      <h3>${wf.id}</h3>
      <p>${wf.stepCount} steps · on failure: ${wf.onFailure}</p>
      <button class="trigger-btn" data-id="${wf.id}">▶ Trigger</button>`;
    card.querySelector('button').addEventListener('click', e => {
      e.stopPropagation();
      triggerWorkflow(wf.id, card.querySelector('button'));
    });
    workflowList.appendChild(card);
  });
}

function renderRunList(runs) {
  if (!runs.length) {
    runList.innerHTML = '<div class="empty">No runs yet. Trigger a workflow →</div>';
    return;
  }
  runList.innerHTML = '';
  runs.forEach(run => {
    const row = el('div', 'run-row');
    if (state.selectedRun?.id === run.id) row.classList.add('active');
    row.innerHTML = `
      <div class="run-wf">${run.workflowId}</div>
      <span class="badge badge-${run.status}">${run.status}</span>
      <div class="run-id">${run.id.slice(0,8)}</div>
      <div class="run-time">${fmtTime(run.createdAt)}</div>`;
    row.addEventListener('click', () => selectRun(run.id));
    runList.appendChild(row);
  });
}

function renderRunDetail(run, steps) {
  state.selectedRun = run;
  runDetail.innerHTML = `
    <div class="detail-card">
      <div class="detail-header">
        <h3>${run.workflowId}</h3>
        <span class="badge badge-${run.status}">${run.status}</span>
        ${run.status === 'RUNNING' ? `<button onclick="cancelRun('${run.id}')" style="margin-left:auto;background:#ef444422;color:#ef4444;border:1px solid #ef444444;border-radius:6px;padding:4px 10px;cursor:pointer;font-size:.75rem;">Cancel</button>` : ''}
      </div>
      <div style="font-size:.75rem;color:var(--muted);margin-bottom:12px;">
        Run ID: <code>${run.id}</code> · Started: ${fmtTime(run.createdAt)}
        ${run.error ? `<div style="color:var(--fail);margin-top:6px;">Error: ${run.error}</div>` : ''}
      </div>
      <div class="step-list" id="step-list-${run.id}"></div>
    </div>`;

  renderSteps(run.id, steps);
  subscribeToRun(run.id);
}

function renderSteps(runId, steps) {
  const container = document.getElementById(`step-list-${runId}`);
  if (!container) return;
  container.innerHTML = '';
  if (!steps.length) {
    container.innerHTML = '<div class="empty" style="padding:16px">Waiting for steps…</div>';
    return;
  }
  steps.forEach(s => {
    const row = el('div', 'step-row');
    row.dataset.status = s.status;
    row.id = `step-${runId}-${s.stepName}`;
    const dur = s.startedAt && s.endedAt
      ? `${Math.round((new Date(s.endedAt) - new Date(s.startedAt))/10)/100}s`
      : s.startedAt ? 'running…' : '';
    row.innerHTML = `
      <span class="badge badge-${s.status}">${s.status}</span>
      <span class="step-name">${s.stepName}</span>
      <span class="step-dur">${dur}</span>
      ${s.error ? `<span class="step-error">${s.error}</span>` : ''}`;
    container.appendChild(row);
  });
}

/* ── Actions ── */
async function triggerWorkflow(id, btn) {
  btn.disabled = true;
  btn.textContent = '…';
  try {
    const run = await api.post(`/api/workflows/${id}/trigger`, {});
    state.runs.unshift(run);
    renderRunList(state.runs);
    await selectRun(run.id);
  } finally {
    btn.disabled = false;
    btn.textContent = '▶ Trigger';
  }
}

async function selectRun(runId) {
  const detail = await api.get(`/api/runs/${runId}`);
  renderRunDetail(detail, detail.steps);
  // Re-render run list to highlight active row
  renderRunList(state.runs);
}

async function cancelRun(runId) {
  await api.del(`/api/runs/${runId}`);
  await selectRun(runId);
}

/* ── WebSocket live feed ── */
function subscribeToRun(runId) {
  if (state.activeWs) state.activeWs.close();

  const ws = new WebSocket(`ws://${location.host}/ws/runs/${runId}`);
  state.activeWs = ws;

  ws.onmessage = ({ data }) => {
    const event = JSON.parse(data);
    handleEvent(runId, event);
  };

  ws.onerror = () => ws.close();
}

async function handleEvent(runId, event) {
  const type = event.type;

  if (type === 'step_started' || type === 'step_completed' || type === 'step_failed') {
    // Re-fetch run detail to get fresh step data
    const detail = await api.get(`/api/runs/${runId}`);
    renderSteps(runId, detail.steps);
  }

  if (type === 'run_completed' || type === 'run_failed' || type === 'run_cancelled') {
    // Refresh everything
    const detail = await api.get(`/api/runs/${runId}`);
    renderRunDetail(detail, detail.steps);
    state.runs = await api.get('/api/runs');
    renderRunList(state.runs);
  }
}

/* ── Utils ── */
function el(tag, cls) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  return e;
}

function fmtTime(iso) {
  if (!iso) return '';
  try { return new Date(iso).toLocaleTimeString(); } catch { return iso; }
}

/* ── Boot ── */
document.addEventListener('DOMContentLoaded', init);
