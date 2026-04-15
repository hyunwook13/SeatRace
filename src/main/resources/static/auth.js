const AUTH_TOKEN_KEY = 'seatrace.token';
const AUTH_USER_KEY = 'seatrace.user';

function loadAuthState() {
  return {
    token: localStorage.getItem(AUTH_TOKEN_KEY) || '',
    user: loadJson(AUTH_USER_KEY),
  };
}

function saveAuthState(token, user) {
  if (token) {
    localStorage.setItem(AUTH_TOKEN_KEY, token);
  } else {
    localStorage.removeItem(AUTH_TOKEN_KEY);
  }

  if (user) {
    localStorage.setItem(AUTH_USER_KEY, JSON.stringify(user));
  } else {
    localStorage.removeItem(AUTH_USER_KEY);
  }
}

function clearAuthState() {
  localStorage.removeItem(AUTH_TOKEN_KEY);
  localStorage.removeItem(AUTH_USER_KEY);
}

function loadJson(key) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function getAuthHeaders(extraHeaders = {}, authRequired = true) {
  const headers = new Headers(extraHeaders);
  const { token } = loadAuthState();
  if (token && authRequired) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  return headers;
}

function renderAuthSummary(target) {
  const { token, user } = loadAuthState();
  if (!target) {
    return;
  }

  if (!token) {
    target.textContent = '미로그인';
    return;
  }

  target.textContent = user
    ? `${user.name || user.email || 'user'} (#${user.id || '?'})`
    : '로그인됨';
}

function isAuthenticated() {
  return Boolean(loadAuthState().token);
}

function currentAppUrl() {
  return window.location.origin + window.location.pathname + window.location.search;
}

function buildLoginUrl(nextUrl = currentAppUrl()) {
  return `/login.html?next=${encodeURIComponent(nextUrl)}`;
}

function buildSignupUrl(nextUrl = currentAppUrl()) {
  return `/signup.html?next=${encodeURIComponent(nextUrl)}`;
}

function buildReservationUrl(eventId) {
  const url = new URL(window.location.origin + '/reservation.html');
  if (eventId) {
    url.searchParams.set('eventId', String(eventId));
  }
  return url.toString();
}

function goToApp() {
  window.location.href = '/';
}

function goToLogin(nextUrl = currentAppUrl()) {
  window.location.href = buildLoginUrl(nextUrl);
}

function goToSignup(nextUrl = currentAppUrl()) {
  window.location.href = buildSignupUrl(nextUrl);
}
