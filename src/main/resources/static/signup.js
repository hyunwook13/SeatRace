const signupForm = document.getElementById('signupForm');
const authState = document.getElementById('authState');
const loginLink = document.getElementById('loginLink');
const nextUrl = new URLSearchParams(window.location.search).get('next') || '/';

renderAuthSummary(authState);
if (loginLink) {
  loginLink.href = `/login.html?next=${encodeURIComponent(nextUrl)}`;
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
    authState.textContent = message || '회원가입 실패';
    return;
  }

  authState.textContent = '회원가입 완료';
  window.location.href = `/login.html?next=${encodeURIComponent(nextUrl)}`;
});

async function safeText(response) {
  try {
    return await response.text();
  } catch {
    return '';
  }
}
