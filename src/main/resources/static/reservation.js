const state = {
  ...loadAuthState(),
  events: [],
  selectedEventId: null,
  seats: [],
  selectedSeatIds: [],
  queue: null,
  queueTokens: {},
  holdResult: null,
};

const els = {
  userState: document.getElementById('userState'),
  loginLink: document.getElementById('loginLink'),
  signupLink: document.getElementById('signupLink'),
  logoutBtn: document.getElementById('logoutBtn'),
  homeLink: document.getElementById('homeLink'),
  pageTitle: document.getElementById('pageTitle'),
  pageCopy: document.getElementById('pageCopy'),
  featuredTitle: document.getElementById('featuredTitle'),
  featuredCopy: document.getElementById('featuredCopy'),
  featuredMeta: document.getElementById('featuredMeta'),
  loadSeatsBtn: document.getElementById('loadSeatsBtn'),
  queueBtn: document.getElementById('queueBtn'),
  reserveBtn: document.getElementById('reserveBtn'),
  seatGrid: document.getElementById('seatGrid'),
  selectionPanel: document.getElementById('selectionPanel'),
  resultPanel: document.getElementById('resultPanel'),
  selectionCount: document.getElementById('selectionCount'),
  toast: document.getElementById('toast'),
};

const urlState = readUrlState();
let toastTimer = null;

bootstrap();

async function bootstrap() {
  bindEvents();
  updateAuthUi();

  if (!state.token) {
    redirectToLogin(currentReservationUrl());
    return;
  }

  await loadEvents();
  applySelectedEventFromUrl();
  await loadSeats();
}

function bindEvents() {
  els.loadSeatsBtn.addEventListener('click', () => loadSeats());
  els.queueBtn.addEventListener('click', () => enterQueue());
  els.reserveBtn.addEventListener('click', () => reserveSelectedSeats());
  els.logoutBtn.addEventListener('click', logout);
}

async function loadEvents() {
  const response = await fetch('/api/events');
  if (!response.ok) {
    showToastMessage('이벤트를 불러오지 못했습니다.');
    return;
  }

  state.events = await response.json();
  if (!state.selectedEventId && state.events.length > 0) {
    state.selectedEventId = Number(state.events[0].id);
  }
  if (urlState.eventId) {
    state.selectedEventId = urlState.eventId;
  }

  renderEventHeader();
}

function applySelectedEventFromUrl() {
  if (urlState.eventId) {
    selectEvent(urlState.eventId, true);
  }
}

async function loadSeats() {
  const event = currentEvent();
  if (!event) {
    return;
  }

  if (!state.token) {
    redirectToLogin(currentReservationUrl());
    return;
  }

  const response = await fetch(`/api/events/${event.id}/seats`, {
    headers: getAuthHeaders(queueTokenHeaders(event.id)),
  });

  if (response.ok) {
    const data = await response.json();
    state.seats = data.seats || [];
    state.queue = null;
    state.selectedSeatIds = state.selectedSeatIds.filter((seatId) =>
      state.seats.some((seat) => seat.seatId === seatId && isSelectable(seat))
    );
    state.holdResult = null;
    renderSeatGrid();
    renderSelectionPanel();
    renderResultPanel();
    showToastMessage('좌석 정보를 불러왔습니다.');
    return;
  }

  if (response.status === 401) {
    redirectToLogin(currentReservationUrl());
    return;
  }

  if (response.status === 429) {
    const data = await safeJson(response);
    state.queue = data;
    state.seats = [];
    state.selectedSeatIds = [];
    renderSeatGrid();
    renderSelectionPanel();
    renderResultPanel();
    showToastMessage('대기열이 필요합니다.');
    return;
  }

  showToastMessage('좌석을 불러오지 못했습니다.');
}

async function enterQueue() {
  const event = currentEvent();
  if (!event) {
    return;
  }

  if (!state.token) {
    redirectToLogin(currentReservationUrl());
    return;
  }

  const response = await request(`/api/events/${event.id}/queue/enter`, {
    method: 'POST',
  });

  if (!response.ok) {
    if (response.status === 401) {
      redirectToLogin(currentReservationUrl());
      return;
    }
    showToastMessage('대기열 진입에 실패했습니다.');
    return;
  }

  state.queue = await response.json();
  rememberQueueToken(event.id, state.queue);
  showToastMessage('대기열에 진입했습니다.');
  if (!state.queue.admitted) {
    await pollQueueUntilAdmitted(event.id);
  }
  if (getQueueToken(event.id)) {
    await loadSeats();
  } else {
    renderResultPanel();
  }
}

async function reserveSelectedSeats() {
  const event = currentEvent();
  if (!event) {
    return;
  }

  if (!state.token) {
    redirectToLogin(currentReservationUrl());
    return;
  }

  if (state.selectedSeatIds.length === 0) {
    showToastMessage('예약할 좌석을 선택하세요.');
    return;
  }

  const response = await request(`/api/events/${event.id}/holds`, {
    method: 'POST',
    headers: queueTokenHeaders(event.id),
    body: JSON.stringify({ seatIds: state.selectedSeatIds }),
  });

  if (response.ok) {
    state.holdResult = await response.json();
    state.selectedSeatIds = [];
    state.queue = null;
    renderSeatGrid();
    renderSelectionPanel();
    renderResultPanel();
    showToastMessage('예약 요청이 완료되었습니다.');
    return;
  }

  if (response.status === 401) {
    redirectToLogin(currentReservationUrl());
    return;
  }

  if (response.status === 429) {
    const data = await safeJson(response);
    state.queue = data;
    renderResultPanel();
    showToastMessage('대기열이 먼저 필요합니다.');
    return;
  }

  const error = await safeJson(response);
  state.holdResult = { error, status: response.status };
  renderResultPanel();
  showToastMessage('예약 요청에 실패했습니다.');
}

function renderEventHeader() {
  const event = currentEvent();
  if (!event) {
    els.featuredTitle.textContent = '이벤트를 불러오는 중';
    els.featuredCopy.textContent = '좌석을 선택할 이벤트를 고르는 중입니다.';
    els.featuredMeta.innerHTML = '';
    return;
  }

  els.pageTitle.textContent = '예약하기';
  els.pageCopy.textContent = `${event.name || `Event ${event.id}`} 좌석을 선택하세요.`;
  els.featuredTitle.textContent = event.name || `Event ${event.id}`;
  els.featuredCopy.textContent = `${event.venue?.name || 'Venue'} · ${formatEventPeriod(event.startAt, event.endAt)}`;
  els.featuredMeta.innerHTML = `
    <span>${escapeHtml(event.status?.value || event.status || 'UNKNOWN')}</span>
    <span>${escapeHtml(String(event.availableSeats ?? 0))} seats left</span>
    <span>${event.id}</span>
  `;
  els.homeLink.href = buildAppUrl({ eventId: event.id });
}

function renderSeatGrid() {
  const event = currentEvent();
  if (!event) {
    els.seatGrid.innerHTML = '<article class="seat-placeholder">이벤트를 먼저 선택하세요.</article>';
    return;
  }

  if (state.seats.length === 0) {
    if (state.queue) {
      els.seatGrid.innerHTML = `
        <article class="seat-placeholder">
          <h3>대기열이 필요합니다</h3>
          <p class="muted">현재 사용자는 좌석 영역에 진입할 수 없습니다. 대기열에 먼저 진입하세요.</p>
        </article>
      `;
      return;
    }

    els.seatGrid.innerHTML = '<article class="seat-placeholder">좌석을 새로고침하세요.</article>';
    return;
  }

  els.seatGrid.innerHTML = state.seats.map((seat) => {
    const selectable = isSelectable(seat);
    const selected = state.selectedSeatIds.includes(seat.seatId);
    return `
      <button
        class="seat-card ${seatClass(seat)} ${selected ? 'selected' : ''}"
        data-seat-id="${seat.seatId}"
        type="button"
        ${selectable ? '' : 'disabled'}
        aria-pressed="${selected ? 'true' : 'false'}"
      >
        <span class="seat-code">${escapeHtml(seat.section || 'S')} ${escapeHtml(seat.rowNo || '-')}-${escapeHtml(seat.seatNo || '-')}</span>
        <strong class="seat-number">${escapeHtml(String(seat.seatId))}</strong>
        <span class="seat-grade">${escapeHtml(seat.grade || '')}</span>
      </button>
    `;
  }).join('');

  els.seatGrid.querySelectorAll('[data-seat-id]').forEach((button) => {
    button.addEventListener('click', () => toggleSeat(Number(button.dataset.seatId)));
  });
}

function renderSelectionPanel() {
  const selectedSeats = state.seats.filter((seat) => state.selectedSeatIds.includes(seat.seatId));
  els.selectionCount.textContent = String(selectedSeats.length);
  els.reserveBtn.disabled = selectedSeats.length === 0;

  if (selectedSeats.length === 0) {
    els.selectionPanel.innerHTML = '<p class="muted">좌석을 선택하면 여기 표시됩니다.</p>';
    return;
  }

  els.selectionPanel.innerHTML = `
    <div class="seat-summary">
      <div class="mini-head">
        <h4>선택된 좌석</h4>
        <span class="chip">${selectedSeats.length}</span>
      </div>
      <div class="seat-list">
        ${selectedSeats.map((seat) => `
          <span class="seat-pill selected">${escapeHtml(seat.section || 'S')} ${escapeHtml(seat.rowNo || '-')}-${escapeHtml(seat.seatNo || '-')}</span>
        `).join('')}
      </div>
    </div>
  `;
}

function renderResultPanel() {
  if (state.holdResult?.reservationId) {
    els.resultPanel.innerHTML = `
      <div class="result-box success">
        <strong>예약 요청 완료</strong>
        <p class="muted">reservationId: ${escapeHtml(String(state.holdResult.reservationId))}</p>
        <p class="muted">만료시각: ${escapeHtml(state.holdResult.expiresAt || '-')}</p>
      </div>
    `;
    return;
  }

  if (state.queue) {
    els.resultPanel.innerHTML = `
      <div class="result-box warning">
        <strong>대기열 안내</strong>
        <p class="muted">position ${escapeHtml(String(state.queue.position ?? '-'))}</p>
        <p class="muted">estimated ${escapeHtml(String(state.queue.estimatedWaitMillis ?? 0))} ms</p>
      </div>
    `;
    return;
  }

  if (state.holdResult?.error) {
    els.resultPanel.innerHTML = `
      <div class="result-box error">
        <strong>예약 실패</strong>
        <p class="muted">${escapeHtml(JSON.stringify(state.holdResult.error))}</p>
      </div>
    `;
    return;
  }

  els.resultPanel.innerHTML = '<p class="muted">예약 버튼을 누르면 결과가 표시됩니다.</p>';
}

function toggleSeat(seatId) {
  const seat = state.seats.find((item) => item.seatId === seatId);
  if (!seat || !isSelectable(seat)) {
    return;
  }

  if (state.selectedSeatIds.includes(seatId)) {
    state.selectedSeatIds = state.selectedSeatIds.filter((id) => id !== seatId);
  } else {
    state.selectedSeatIds = [...state.selectedSeatIds, seatId];
  }

  renderSeatGrid();
  renderSelectionPanel();
}

function selectEvent(eventId, silent = false) {
  state.selectedEventId = eventId;
  state.seats = [];
  state.selectedSeatIds = [];
  state.queue = null;
  state.holdResult = null;
  const cachedToken = loadQueueToken(eventId);
  if (cachedToken) {
    state.queueTokens[eventId] = cachedToken;
  }
  renderEventHeader();
  renderSeatGrid();
  renderSelectionPanel();
  renderResultPanel();
  if (!silent) {
    history.replaceState({}, '', buildReservationUrl(eventId));
  }
}

function currentEvent() {
  return findEvent(state.selectedEventId) || state.events[0] || null;
}

function findEvent(eventId) {
  return state.events.find((event) => event.id === eventId) || null;
}

function isSelectable(seat) {
  return String(seat.status || '').toUpperCase() === 'AVAILABLE';
}

function seatClass(seat) {
  const status = String(seat.status || '').toUpperCase();
  if (status === 'AVAILABLE') return 'available';
  if (status === 'HOLD') return 'hold';
  if (status === 'RESERVED') return 'reserved';
  return 'unavailable';
}

function applySelectedEventFromUrl() {
  if (!urlState.eventId) {
    return;
  }
  const event = findEvent(urlState.eventId);
  if (event) {
    selectEvent(event.id, true);
  }
}

function currentReservationUrl() {
  return buildReservationUrl(state.selectedEventId || urlState.eventId || '');
}

function logout() {
  clearAuthState();
  state.token = '';
  state.user = null;
  updateAuthUi();
  redirectToLogin(buildReservationUrl(state.selectedEventId || urlState.eventId || ''));
}

function updateAuthUi() {
  const { token, user } = loadAuthState();
  state.token = token;
  state.user = user;

  if (state.token) {
    els.userState.textContent = user ? `${user.name || user.email || 'user'} 님` : '로그인됨';
    els.loginLink.classList.add('hidden');
    els.signupLink.classList.add('hidden');
    els.logoutBtn.classList.remove('hidden');
  } else {
    els.userState.textContent = '미로그인';
    els.loginLink.classList.remove('hidden');
    els.signupLink.classList.remove('hidden');
    els.loginLink.href = buildLoginUrl(currentReservationUrl());
    els.signupLink.href = buildSignupUrl(currentReservationUrl());
    els.logoutBtn.classList.add('hidden');
  }
}

function buildAppUrl({ eventId = null } = {}) {
  const url = new URL(window.location.origin + '/');
  if (eventId) {
    url.searchParams.set('eventId', String(eventId));
  }
  return url.toString();
}

function readUrlState() {
  const params = new URLSearchParams(window.location.search);
  return {
    eventId: params.get('eventId') ? Number(params.get('eventId')) : null,
  };
}

function formatEventPeriod(startAt, endAt) {
  if (!startAt || !endAt) {
    return '일정 확인 중';
  }
  const start = new Date(startAt);
  const end = new Date(endAt);
  return `${formatDateTime(start)} - ${formatDateTime(end)}`;
}

function formatDateTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value || '-');
  }
  return date.toLocaleString('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function showToastMessage(message) {
  if (!els.toast) return;
  els.toast.textContent = message;
  els.toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove('show'), 2200);
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const authHeaders = getAuthHeaders(headers, true);
  if (authHeaders.has('Authorization')) {
    headers.set('Authorization', authHeaders.get('Authorization'));
  }

  return fetch(path, {
    ...options,
    headers,
  });
}

function queueTokenHeaders(eventId) {
  const token = getQueueToken(eventId);
  return token ? { 'X-Queue-Token': token } : {};
}

function rememberQueueToken(eventId, queue) {
  if (!queue?.admissionToken) {
    return;
  }
  state.queueTokens[eventId] = queue.admissionToken;
  const expiresAt = Number(queue.admissionExpiresAtMillis || 0);
  localStorage.setItem(queueTokenStorageKey(eventId), JSON.stringify({
    token: queue.admissionToken,
    expiresAt,
  }));
}

function getQueueToken(eventId) {
  if (state.queueTokens[eventId]) {
    return state.queueTokens[eventId];
  }
  const token = loadQueueToken(eventId);
  if (token) {
    state.queueTokens[eventId] = token;
  }
  return token;
}

function loadQueueToken(eventId) {
  try {
    const raw = localStorage.getItem(queueTokenStorageKey(eventId));
    if (!raw) {
      return '';
    }
    const parsed = JSON.parse(raw);
    if (!parsed.token || Number(parsed.expiresAt || 0) <= Date.now()) {
      localStorage.removeItem(queueTokenStorageKey(eventId));
      return '';
    }
    return parsed.token;
  } catch {
    localStorage.removeItem(queueTokenStorageKey(eventId));
    return '';
  }
}

function queueTokenStorageKey(eventId) {
  return `seatrace.queueToken.${eventId}`;
}

async function pollQueueUntilAdmitted(eventId) {
  for (let i = 0; i < 10; i += 1) {
    await sleep(300);
    const response = await request(`/api/events/${eventId}/queue/status`, {
      method: 'GET',
    });
    if (!response.ok) {
      return;
    }
    state.queue = await response.json();
    rememberQueueToken(eventId, state.queue);
    if (state.queue.admitted && state.queue.admissionToken) {
      return;
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function safeJson(response) {
  try {
    return await response.clone().json();
  } catch {
    return { message: await response.text() };
  }
}

function redirectToLogin(nextUrl) {
  window.location.href = buildLoginUrl(nextUrl);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}
