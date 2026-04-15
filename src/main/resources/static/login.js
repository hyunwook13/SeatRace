const loginForm = document.getElementById('loginForm');
const authState = document.getElementById('authState');
const signupLink = document.getElementById('signupLink');
const nextUrl = new URLSearchParams(window.location.search).get('next') || '/';

renderAuthSummary(authState);
if (signupLink) {
  signupLink.href = `/signup.html?next=${encodeURIComponent(nextUrl)}`;
}

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();

  const form = new FormData(event.currentTarget);
  const username = String(form.get('username') || '').trim();
  const password = String(form.get('password') || '');

  const response = await fetch('/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({ username, password }).toString(),
  });

  if (!response.ok) {
    const message = await safeText(response);
    authState.textContent = message || '로그인 실패';
    return;
  }

  const data = await response.json();
  saveAuthState(data.accessToken, data.user);
  window.location.href = nextUrl;
});

async function safeText(response) {
  try {
    return await response.text();
  } catch {
    return '';
  }
}
