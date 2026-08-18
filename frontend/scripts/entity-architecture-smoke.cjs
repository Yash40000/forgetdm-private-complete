const { chromium } = require('@playwright/test');
const baseUrl = process.env.SMOKE_BASE_URL || 'http://localhost:3000';

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  const errors = [];
  await page.route('**/api/auth/me', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ authenticated: true, user: { username: 'admin', displayName: 'Platform Admin', roles: ['ADMIN'], permissions: ['admin.all'] } })
  }));
  await page.route('**/api/discovery/graph/**', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      nodes: [
        { id: 'customers', label: 'customers', piiCount: 2, piiColumns: [{ column: 'first_name', piiType: 'FIRST_NAME' }] },
        { id: 'accounts', label: 'accounts', piiCount: 1, piiColumns: [{ column: 'account_no', piiType: 'BANK_ACCOUNT' }] },
        { id: 'beneficiaries', label: 'beneficiaries', piiCount: 0, piiColumns: [] },
        { id: 'branches', label: 'branches', piiCount: 0, piiColumns: [] },
        { id: 'loans', label: 'loans', piiCount: 0, piiColumns: [] }
      ],
      edges: [
        { id: 'customer-account', from: 'customers', to: 'accounts', pkColumn: 'customer_id', fkColumn: 'customer_id' },
        { id: 'account-loan', from: 'accounts', to: 'loans', pkColumn: 'account_id', fkColumn: 'account_id' }
      ]
    })
  }));
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(`console: ${message.text()}`);
  });
  await page.addInitScript(() => {
    localStorage.setItem('forgetdm.entity-architecture.applications', JSON.stringify([{
      id: 'test-app',
      label: 'Banking',
      dataSourceId: 1,
      dataSourceName: 'sourceDB',
      schema: 'test',
      tables: ['customers', 'accounts', 'beneficiaries', 'branches', 'loans']
    }]));
    localStorage.setItem('forgetdm.entity-architecture.cross-links', '[]');
  });
  const response = await page.goto(`${baseUrl}/entity-architecture`, {
    waitUntil: 'domcontentloaded',
    timeout: 120_000
  });
  await page.getByRole('heading', { name: 'Entity Architecture' }).waitFor({ timeout: 90_000 });
  await page.waitForTimeout(3_000);
  console.log(JSON.stringify({
    status: response && response.status(),
    body: (await page.locator('body').innerText()).slice(0, 800),
    errors
  }, null, 2));
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
