const failEls = {
  resultTitle: document.getElementById('resultTitle'),
  resultMessage: document.getElementById('resultMessage'),
  resultDetail: document.getElementById('resultDetail'),
};

bootstrap();

async function bootstrap() {
  const params = new URLSearchParams(window.location.search);
  const orderId = params.get('orderId');
  const code = params.get('code');
  const message = params.get('message');

  if (!orderId) {
    renderError('orderId가 없습니다.');
    return;
  }

  try {
    const response = await fetch(`/api/payments/fail?orderId=${encodeURIComponent(orderId)}&message=${encodeURIComponent(message || code || 'payment_failed')}`, {
      method: 'POST',
      headers: getAuthHeaders(),
    });

    const data = await safeJson(response);
    if (!response.ok) {
      throw new Error(data?.message || `HTTP ${response.status}`);
    }

    failEls.resultTitle.textContent = '결제가 실패했습니다';
    failEls.resultMessage.textContent = '실패 내역을 저장했고 예약은 취소했습니다.';
    failEls.resultDetail.textContent = JSON.stringify({ code, message, data }, null, 2);
  } catch (error) {
    renderError(error?.message || String(error));
  }
}

function renderError(message) {
  failEls.resultTitle.textContent = '결제 실패 처리에 오류가 있습니다';
  failEls.resultMessage.textContent = '서버 처리 중 문제가 발생했습니다.';
  failEls.resultDetail.textContent = message;
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}
