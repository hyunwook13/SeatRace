const signupForm = document.getElementById('signupForm');
const authState = document.getElementById('authState');
const loginLink = document.getElementById('loginLink');
const nextUrl = new URLSearchParams(window.location.search).get('next') || '/';

if (loginLink) {
  loginLink.href = `/login.html?next=${encodeURIComponent(nextUrl)}`;
}

if (isAuthenticated()) {
  window.location.replace(resolveAuthenticatedRedirect(nextUrl));
}

signupForm.addEventListener('submit', async (event) => {
  event.preventDefault();

  const form = new FormData(event.currentTarget);
  const payload = Object.fromEntries(form.entries());

  const response = await fetch('/api/auth/signup', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await safeText(response);
    authState.hidden = false;
    authState.textContent = message || '회원가입 실패';
    return;
  }

  window.location.replace(`/login.html?next=${encodeURIComponent(nextUrl)}`);
});

async function safeText(response) {
  try {
    return await response.text();
  } catch {
    return '';
  }
}

function resolveAuthenticatedRedirect(requestedNextUrl) {
  const nextPath = normalizePathname(requestedNextUrl);
  const { user } = loadAuthState();
  const role = String(user?.role || '').toUpperCase();

  if (role === 'ADMIN' && (nextPath === '/' || nextPath === '/index.html' || !nextPath)) {
    return '/admin.html';
  }

  return requestedNextUrl || '/';
}

function normalizePathname(value) {
  try {
    return new URL(value, window.location.origin).pathname;
  } catch {
    return '/';
  }
}
