import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productCount = Number(__ENV.PRODUCT_COUNT || 20);

export const options = {
  scenarios: {
    steady_reads: {
      executor: 'ramping-vus',
      startVUs: Number(__ENV.START_VUS || 20),
      stages: [
        { duration: __ENV.RAMP_UP || '30s', target: Number(__ENV.TARGET_VUS || 220) },
        { duration: __ENV.HOLD || '120s', target: Number(__ENV.TARGET_VUS || 220) },
        { duration: __ENV.RAMP_DOWN || '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const productId = 1 + (__VU % productCount);
  const listRes = http.get(`${baseUrl}/api/products`);
  check(listRes, {
    'list status 200': (r) => r.status === 200,
  });

  const detailRes = http.get(`${baseUrl}/api/products/${productId}`);
  check(detailRes, {
    'detail status 200': (r) => r.status === 200,
  });

  sleep(Number(__ENV.SLEEP_SECONDS || 0.05));
}
