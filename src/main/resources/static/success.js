const successEls = {
  resultTitle: document.getElementById('resultTitle'),
  resultMessage: document.getElementById('resultMessage'),
  resultDetail: document.getElementById('resultDetail'),
};

bootstrap();

async function bootstrap() {
  const params = new URLSearchParams(window.location.search);
  const paymentKey = params.get('paymentKey');
  const orderId = params.get('orderId');
  const amount = Number(params.get('amount') || 0);

  if (!paymentKey || !orderId || !amount) {
    renderError('결제 승인 정보를 찾을 수 없습니다.');
    return;
  }

  try {
    const response = await fetch('/api/payments/confirm', {
      method: 'POST',
      headers: getAuthHeaders({
        'Content-Type': 'application/json',
      }),
      body: JSON.stringify({ paymentKey, orderId, amount }),
    });

    const data = await safeJson(response);
    if (!response.ok) {
      throw new Error(data?.message || `HTTP ${response.status}`);
    }

    successEls.resultTitle.textContent = '결제가 승인되었습니다';
    successEls.resultMessage.textContent = '서버가 결제 승인과 예약 확정을 완료했습니다.';
    successEls.resultDetail.textContent = JSON.stringify(data, null, 2);
  } catch (error) {
    renderError(error?.message || String(error));
  }
}

function renderError(message) {
  successEls.resultTitle.textContent = '결제 승인에 실패했습니다';
  successEls.resultMessage.textContent = '서버 승인 단계에서 오류가 발생했습니다.';
  successEls.resultDetail.textContent = message;
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}
