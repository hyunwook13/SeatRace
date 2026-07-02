const state = loadAuthState();
const maxRecentResults = 4;

const els = {
  userState: document.getElementById('userState'),
  logoutBtn: document.getElementById('logoutBtn'),
  venueForm: document.getElementById('venueForm'),
  eventForm: document.getElementById('eventForm'),
  sectionForm: document.getElementById('sectionForm'),
  physicalSeatForm: document.getElementById('physicalSeatForm'),
  eventSeatForm: document.getElementById('eventSeatForm'),
  creationResult: document.getElementById('creationResult'),
  responseLog: document.getElementById('responseLog'),
  clearLogBtn: document.getElementById('clearLogBtn'),
  toast: document.getElementById('toast'),
};

bootstrap();

function bootstrap() {
  updateAuthUi();
  ensureAdminAccess();
  bindEvents();
}

function bindEvents() {
  els.logoutBtn.addEventListener('click', logout);
  els.clearLogBtn.addEventListener('click', () => {
    els.responseLog.textContent = '아직 작업이 없습니다.';
    renderCreationResults([]);
  });
  els.venueForm.addEventListener('submit', (event) => submitVenue(event));
  els.eventForm.addEventListener('submit', (event) => submitEvent(event));
  els.sectionForm.addEventListener('submit', (event) => submitSeatSection(event));
  els.physicalSeatForm.addEventListener('submit', (event) => submitPhysicalSeats(event));
  els.eventSeatForm.addEventListener('submit', (event) => submitEventSeats(event));
}

function ensureAdminAccess() {
  const role = String(state.user?.role || '').toUpperCase();
  if (!state.token) {
    window.location.href = buildLoginUrl('/admin.html');
    return;
  }

  if (role !== 'ADMIN') {
    els.responseLog.textContent = '관리자 권한이 필요합니다.';
    showToastMessage('관리자 계정으로 로그인하세요.');
    window.location.href = '/';
  }
}

async function submitVenue(event) {
  event.preventDefault();
  try {
    const form = new FormData(event.currentTarget);
    const payload = {
      name: String(form.get('name') || '').trim(),
      location: String(form.get('location') || '').trim(),
    };

    const response = await requestJson('/api/admin/venues', {
      method: 'POST',
      body: payload,
    });
    writeLog('공연장 생성', response);
    appendCreationResult('공연장 생성', response);
    setFormValue(els.sectionForm, 'venueId', response.id);
    setFormValue(els.eventForm, 'venueId', response.id);
  } catch (error) {
    writeLog('공연장 생성 실패', { message: error?.message || String(error) });
    appendCreationResult('공연장 생성 실패', { message: error?.message || String(error) }, 'error');
  }
}

async function submitEvent(event) {
  event.preventDefault();
  try {
    const form = new FormData(event.currentTarget);
    const payload = {
      venueId: Number(form.get('venueId') || 0),
      name: String(form.get('name') || '').trim(),
      startAt: toDateTimeValue(String(form.get('startAt') || '')),
      endAt: toDateTimeValue(String(form.get('endAt') || '')),
    };

    const response = await requestJson('/api/admin/events', {
      method: 'POST',
      body: payload,
    });
    writeLog('이벤트 생성', response);
    appendCreationResult('이벤트 생성', response);
    setFormValue(els.eventSeatForm, 'eventId', response.id);
  } catch (error) {
    writeLog('이벤트 생성 실패', { message: error?.message || String(error) });
    appendCreationResult('이벤트 생성 실패', { message: error?.message || String(error) }, 'error');
  }
}

async function submitSeatSection(event) {
  event.preventDefault();
  try {
    const form = new FormData(event.currentTarget);
    const venueId = Number(form.get('venueId') || 0);
    const payload = {
      name: String(form.get('section') || '').trim(),
    };

    const response = await requestJson(`/api/admin/venues/${venueId}/seat-sections`, {
      method: 'POST',
      body: payload,
    });
    writeLog('구역 생성', response);
    appendCreationResult('구역 생성', response);
    setFormValue(els.physicalSeatForm, 'seatSectionId', response.id);
    setFormValue(els.eventSeatForm, 'seatSectionId', response.id);
  } catch (error) {
    writeLog('구역 생성 실패', { message: error?.message || String(error) });
    appendCreationResult('구역 생성 실패', { message: error?.message || String(error) }, 'error');
  }
}

async function submitPhysicalSeats(event) {
  event.preventDefault();
  try {
    const form = new FormData(event.currentTarget);
    const payload = {
      count: Number(form.get('count') || 0),
    };

    const seatSectionId = Number(form.get('seatSectionId') || 0);
    const response = await requestJson(`/api/admin/seat-sections/${seatSectionId}/seats/generate`, {
      method: 'POST',
      body: payload,
    });
    writeLog('물리 좌석 생성', response);
    appendCreationResult('물리 좌석 생성', response);
  } catch (error) {
    writeLog('물리 좌석 생성 실패', { message: error?.message || String(error) });
    appendCreationResult('물리 좌석 생성 실패', { message: error?.message || String(error) }, 'error');
  }
}

async function submitEventSeats(event) {
  event.preventDefault();
  try {
    const form = new FormData(event.currentTarget);
    const eventId = Number(form.get('eventId') || 0);
    const seatSectionId = Number(form.get('seatSectionId') || 0);
    const payload = {
      grade: String(form.get('grade') || 'VIP').trim(),
      price: Number(form.get('price') || 0),
    };

    const response = await requestJson(`/api/admin/events/${eventId}/seat-sections/${seatSectionId}/event-seats`, {
      method: 'POST',
      body: payload,
    });
    writeLog('판매 좌석 생성', response);
    appendCreationResult('판매 좌석 생성', response);
  } catch (error) {
    writeLog('판매 좌석 생성 실패', { message: error?.message || String(error) });
    appendCreationResult('판매 좌석 생성 실패', { message: error?.message || String(error) }, 'error');
  }
}

async function requestJson(path, { method = 'GET', body } = {}) {
  const response = await fetch(path, {
    method,
    headers: getAuthHeaders({
      'Content-Type': 'application/json',
    }),
    body: body ? JSON.stringify(body) : undefined,
  });

  const data = await safeJson(response);
  if (!response.ok) {
    throw new Error(data?.message || data?.error || `HTTP ${response.status}`);
  }
  return data;
}

function writeLog(title, data) {
  els.responseLog.textContent = `${title}\n${JSON.stringify(data, null, 2)}`;
  showToastMessage(title.includes('실패') ? title : `${title} 완료`);
}

function appendCreationResult(title, data, tone = 'success') {
  if (!els.creationResult) {
    return;
  }

  const entry = {
    title,
    data,
    tone,
    timestamp: new Date().toLocaleTimeString('ko-KR', { hour12: false }),
  };

  const current = Array.isArray(els.creationResult.__results) ? els.creationResult.__results : [];
  const next = [entry, ...current].slice(0, maxRecentResults);
  els.creationResult.__results = next;
  renderCreationResults(next);
}

function renderCreationResults(results) {
  if (!els.creationResult) {
    return;
  }

  if (!results.length) {
    els.creationResult.innerHTML = `
      <div class="result-box">
        <strong>아직 생성된 데이터가 없습니다.</strong>
        <span class="small muted">공연장, 구역, 물리 좌석, 판매 좌석을 만들면 여기에서 바로 확인할 수 있습니다.</span>
      </div>
    `;
    return;
  }

  els.creationResult.innerHTML = results.map((entry) => renderResultBox(entry)).join('');
}

function renderResultBox(entry) {
  const data = entry.data || {};
  const summary = buildResultSummary(data);
  const detail = buildResultDetail(data);

  return `
    <article class="result-box ${entry.tone === 'error' ? 'error' : 'success'}">
      <div class="mini-head">
        <strong>${escapeHtml(entry.title)}</strong>
        <span class="chip">${escapeHtml(entry.timestamp)}</span>
      </div>
      <div class="small muted">${escapeHtml(summary)}</div>
      ${detail}
    </article>
  `;
}

function buildResultSummary(data) {
  if (data && typeof data === 'object') {
    if (typeof data.createdCount === 'number') {
      const seatCount = Array.isArray(data.createdSeats) ? data.createdSeats.length : data.createdCount;
      return `createdCount=${data.createdCount}, displayed=${seatCount}`;
    }

    if (data.id != null) {
      return `id=${data.id}${data.name ? `, name=${data.name}` : ''}`;
    }

    if (data.message) {
      return data.message;
    }
  }

  return typeof data === 'string' ? data : JSON.stringify(data);
}

function buildResultDetail(data) {
  if (!data || typeof data !== 'object') {
    return '';
  }

  if (Array.isArray(data.createdSeats) && data.createdSeats.length > 0) {
    const chips = data.createdSeats.slice(0, 20).map((seat) => {
      const seatLabel = seat.seatNo ? `${seat.section}${seat.rowNo}-${seat.seatNo}` : `#${seat.id || ''}`;
      const extra = seat.grade ? ` · ${seat.grade}` : '';
      return `<span class="chip">${escapeHtml(seatLabel)}${escapeHtml(extra)}</span>`;
    }).join('');

    return `
      <div class="chips">
        ${chips}
      </div>
    `;
  }

  if (data.message) {
    return `<div class="small muted">${escapeHtml(data.message)}</div>`;
  }

  return `<pre class="admin-log" style="min-height:auto;max-height:240px;margin:0;">${escapeHtml(JSON.stringify(data, null, 2))}</pre>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function setFormValue(form, name, value) {
  if (!form || value == null) {
    return;
  }

  const input = form.querySelector(`[name="${name}"]`);
  if (input) {
    input.value = String(value);
  }
}

function toDateTimeValue(value) {
  if (!value) {
    return value;
  }
  return value.length === 16 ? `${value}:00` : value;
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function updateAuthUi() {
  const { token, user } = loadAuthState();
  state.token = token;
  state.user = user;

  if (state.token) {
    els.userState.textContent = user ? `${user.name || user.email || 'user'} 님` : '로그인됨';
    els.logoutBtn.classList.remove('hidden');
  } else {
    els.userState.textContent = '';
    els.logoutBtn.classList.add('hidden');
  }
}

function logout() {
  clearAuthState();
  state.token = '';
  state.user = null;
  showToastMessage('로그아웃했습니다.');
  window.location.href = '/';
}

function showToastMessage(message) {
  if (!els.toast) return;
  els.toast.textContent = message;
  els.toast.classList.add('show');
  clearTimeout(window.__adminToastTimer);
  window.__adminToastTimer = setTimeout(() => els.toast.classList.remove('show'), 2200);
}
