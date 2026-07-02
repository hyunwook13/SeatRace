const FAVORITES_KEY = 'seatrace.favorites';

const state = {
  ...loadAuthState(),
  events: [],
  selectedEventId: null,
  favorites: loadList(FAVORITES_KEY),
};

const els = {
  userState: document.getElementById('userState'),
  loginLink: document.getElementById('loginLink'),
  signupLink: document.getElementById('signupLink'),
  logoutBtn: document.getElementById('logoutBtn'),
  refreshEventsBtn: document.getElementById('refreshEventsBtn'),
  featuredTitle: document.getElementById('featuredTitle'),
  featuredCopy: document.getElementById('featuredCopy'),
  featuredMeta: document.getElementById('featuredMeta'),
  featuredReserveBtn: document.getElementById('featuredReserveBtn'),
  featuredLikeBtn: document.getElementById('featuredLikeBtn'),
  eventsGrid: document.getElementById('eventsGrid'),
  selectedPanel: document.getElementById('selectedPanel'),
  favoritesList: document.getElementById('favoritesList'),
  favoritesCount: document.getElementById('favoritesCount'),
  toast: document.getElementById('toast'),
};

const urlState = readUrlState();
let toastTimer = null;

bootstrap();

async function bootstrap() {
  bindEvents();
  updateAuthUi();
  await loadEvents();
}

function bindEvents() {
  els.refreshEventsBtn.addEventListener('click', () => loadEvents(true));
  els.featuredReserveBtn.addEventListener('click', () => reserveSelectedEvent());
  els.featuredLikeBtn.addEventListener('click', () => toggleFavoriteForSelected());
  els.logoutBtn.addEventListener('click', logout);
}

async function loadEvents(showToast = false) {
  const response = await fetch('/api/events');
  if (!response.ok) {
    showToastMessage('이벤트를 불러오지 못했습니다.');
    return;
  }

  state.events = sortEventsByLatest(await response.json());
  if (urlState.eventId) {
    state.selectedEventId = urlState.eventId;
  }

  renderEvents();
  renderFeaturedEvent();
  renderSidebar();

  if (showToast) {
    showToastMessage('이벤트를 새로고침했습니다.');
  }
}

function renderEvents() {
  if (!els.eventsGrid) {
    return;
  }

  if (state.events.length === 0) {
    els.eventsGrid.innerHTML = '<article class="event-card placeholder">이벤트가 없습니다.</article>';
    return;
  }

  els.eventsGrid.innerHTML = state.events.map((event) => {
    const active = event.id === state.selectedEventId;
    const liked = isFavorite(event.id);
    return `
      <article class="event-card ${active ? 'active' : ''}" data-event-card="${event.id}">
        <button class="event-select-area" data-select-event="${event.id}" type="button">
          <div class="event-card-top">
            <span class="event-badge">${escapeHtml(event.status?.value || event.status || 'UNKNOWN')}</span>
            <span class="event-badge subtle">${escapeHtml(event.venue?.name || 'Venue')}</span>
          </div>
          <h3>${escapeHtml(event.name || `Event ${event.id}`)}</h3>
          <p class="muted">${escapeHtml(formatEventPeriod(event.startAt, event.endAt))}</p>
          <div class="event-metrics">
            <span>${escapeHtml(String(event.seatCount ?? 0))} seats</span>
            <span>${escapeHtml(String(event.availableSeats ?? 0))} available</span>
          </div>
        </button>
        <div class="event-actions">
          <button class="ghost-button ${liked ? 'liked' : ''}" data-favorite-event="${event.id}" type="button">
            ${liked ? '관심 해제' : '관심 등록'}
          </button>
          <button class="primary-button" data-reserve-event="${event.id}" type="button">예약하기</button>
        </div>
      </article>
    `;
  }).join('');

  els.eventsGrid.querySelectorAll('[data-select-event]').forEach((button) => {
    button.addEventListener('click', () => selectEvent(Number(button.dataset.selectEvent)));
  });

  els.eventsGrid.querySelectorAll('[data-favorite-event]').forEach((button) => {
    button.addEventListener('click', () => toggleFavoriteForEvent(Number(button.dataset.favoriteEvent)));
  });

  els.eventsGrid.querySelectorAll('[data-reserve-event]').forEach((button) => {
    button.addEventListener('click', () => reserveEvent(Number(button.dataset.reserveEvent)));
  });
}

function renderFeaturedEvent() {
  const event = currentEvent();
  if (!event) {
    els.featuredTitle.textContent = '이벤트를 선택하세요';
    els.featuredCopy.textContent = '추천 리스트에서 원하는 이벤트를 고르면 상세 정보가 표시됩니다.';
    els.featuredMeta.innerHTML = '';
    els.featuredReserveBtn.disabled = true;
    els.featuredLikeBtn.disabled = true;
    return;
  }

  els.featuredTitle.textContent = event.name || `Event ${event.id}`;
  els.featuredCopy.textContent = `${event.venue?.name || 'Venue'} · ${formatEventPeriod(event.startAt, event.endAt)}`;
  els.featuredMeta.innerHTML = `
    <span>${escapeHtml(event.status?.value || event.status || 'UNKNOWN')}</span>
    <span>${escapeHtml(String(event.availableSeats ?? 0))} seats left</span>
    <span>${isFavorite(event.id) ? '관심 등록됨' : '관심 가능'}</span>
  `;
  els.featuredLikeBtn.textContent = isFavorite(event.id) ? '관심 해제' : '관심 등록';
  els.featuredReserveBtn.disabled = false;
  els.featuredLikeBtn.disabled = false;
}

function renderSidebar() {
  const event = currentEvent();
  if (!event) {
    els.selectedPanel.innerHTML = '<p class="muted">아직 선택된 이벤트가 없습니다.</p>';
    renderFavorites();
    updateAuthUi();
    return;
  }

  els.selectedPanel.innerHTML = `
    <div class="selected-hero">
      <span class="event-badge">${escapeHtml(event.status?.value || event.status || 'UNKNOWN')}</span>
      <h3>${escapeHtml(event.name || `Event ${event.id}`)}</h3>
      <p class="muted">${escapeHtml(event.venue?.name || 'Venue')} · ${escapeHtml(formatEventPeriod(event.startAt, event.endAt))}</p>
    </div>
    <div class="selected-stats">
      <div>
        <span>Available</span>
        <strong>${escapeHtml(String(event.availableSeats ?? 0))}</strong>
      </div>
      <div>
        <span>Total</span>
        <strong>${escapeHtml(String(event.seatCount ?? 0))}</strong>
      </div>
    </div>
    <div class="selected-actions">
      <button class="primary-button" id="sidebarReserveBtn" type="button">예약하기</button>
      <button class="ghost-button ${isFavorite(event.id) ? 'liked' : ''}" id="sidebarFavoriteBtn" type="button">
        ${isFavorite(event.id) ? '관심 해제' : '관심 등록'}
      </button>
    </div>
  `;

  document.getElementById('sidebarReserveBtn').addEventListener('click', () => reserveEvent(event.id));
  document.getElementById('sidebarFavoriteBtn').addEventListener('click', () => toggleFavoriteForSelected());
  renderFavorites();
  updateAuthUi();
}

function renderFavorites() {
  const favorites = favoriteEvents();
  els.favoritesCount.textContent = String(favorites.length);

  if (favorites.length === 0) {
    els.favoritesList.innerHTML = '<p class="muted">관심 등록한 이벤트가 없습니다.</p>';
    return;
  }

  els.favoritesList.innerHTML = favorites.map((event) => `
    <button class="favorite-item" type="button" data-favorite-jump="${event.id}">
      <strong>${escapeHtml(event.name || `Event ${event.id}`)}</strong>
      <span>${escapeHtml(event.venue?.name || 'Venue')}</span>
    </button>
  `).join('');

  els.favoritesList.querySelectorAll('[data-favorite-jump]').forEach((button) => {
    button.addEventListener('click', () => selectEvent(Number(button.dataset.favoriteJump)));
  });
}

function selectEvent(eventId) {
  state.selectedEventId = eventId;
  renderEvents();
  renderFeaturedEvent();
  renderSidebar();
  history.replaceState({}, '', buildAppUrl({ eventId }));
}

function reserveSelectedEvent() {
  const event = currentEvent();
  if (!event) {
    return;
  }
  reserveEvent(event.id);
}

function reserveEvent(eventId) {
  if (!state.token) {
    redirectToLogin(buildReservationUrl(eventId));
    return;
  }

  window.location.href = buildReservationUrl(eventId);
}

async function toggleFavoriteForSelected() {
  const event = currentEvent();
  if (!event) {
    return;
  }
  await toggleFavoriteForEvent(event.id);
}

async function toggleFavoriteForEvent(eventId, silent = false) {
  if (!state.token) {
    redirectToLogin(currentAppUrl());
    return;
  }

  const exists = state.favorites.includes(eventId);
  state.favorites = exists
    ? state.favorites.filter((id) => id !== eventId)
    : [...state.favorites, eventId];
  saveList(FAVORITES_KEY, state.favorites);
  renderEvents();
  renderFeaturedEvent();
  renderSidebar();

  if (!silent) {
    showToastMessage(exists ? '관심을 해제했습니다.' : '관심 이벤트에 추가했습니다.');
  }
}

function logout() {
  clearAuthState();
  state.token = '';
  state.user = null;
  updateAuthUi();
  renderSidebar();
  showToastMessage('로그아웃했습니다.');
}

function updateAuthUi() {
  const { token, user } = loadAuthState();
  state.token = token;
  state.user = user;

  if (state.token) {
    els.userState.textContent = user ? `${user.name || user.email || 'user'} 님` : '로그인됨';
    els.userState.classList.remove('hidden');
    els.loginLink.classList.add('hidden');
    els.signupLink.classList.add('hidden');
    els.logoutBtn.classList.remove('hidden');
  } else {
    els.userState.textContent = '';
    els.userState.classList.add('hidden');
    els.loginLink.classList.remove('hidden');
    els.signupLink.classList.remove('hidden');
    els.loginLink.href = buildLoginUrl(currentAppUrl());
    els.signupLink.href = buildSignupUrl(currentAppUrl());
    els.logoutBtn.classList.add('hidden');
  }

  els.featuredLikeBtn.textContent = currentEvent() && isFavorite(currentEvent().id) ? '관심 해제' : '관심 등록';
}

function currentEvent() {
  return findEvent(state.selectedEventId) || null;
}

function findEvent(eventId) {
  return state.events.find((event) => event.id === eventId) || null;
}

function favoriteEvents() {
  return state.favorites
    .map((id) => findEvent(id))
    .filter(Boolean);
}

function isFavorite(eventId) {
  return state.favorites.includes(eventId);
}

function readUrlState() {
  const params = new URLSearchParams(window.location.search);
  return {
    eventId: params.get('eventId') ? Number(params.get('eventId')) : null,
  };
}

function buildAppUrl({ eventId = null } = {}) {
  const url = new URL(window.location.origin + '/');
  if (eventId) {
    url.searchParams.set('eventId', String(eventId));
  }
  return url.toString();
}

function sortEventsByLatest(events) {
  return [...events].sort((left, right) => {
    const leftCreatedAt = Date.parse(left?.createdAt || '');
    const rightCreatedAt = Date.parse(right?.createdAt || '');
    if (!Number.isNaN(leftCreatedAt) && !Number.isNaN(rightCreatedAt) && leftCreatedAt !== rightCreatedAt) {
      return rightCreatedAt - leftCreatedAt;
    }

    const leftStartAt = Date.parse(left?.startAt || '');
    const rightStartAt = Date.parse(right?.startAt || '');
    if (!Number.isNaN(leftStartAt) && !Number.isNaN(rightStartAt) && leftStartAt !== rightStartAt) {
      return rightStartAt - leftStartAt;
    }

    return Number(right?.id || 0) - Number(left?.id || 0);
  });
}

function currentAppUrl() {
  return buildAppUrl({ eventId: state.selectedEventId });
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

function loadList(key) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function saveList(key, values) {
  localStorage.setItem(key, JSON.stringify(values));
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
