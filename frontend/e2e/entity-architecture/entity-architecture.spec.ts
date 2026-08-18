import { expect, test } from '@playwright/test';

test('saved table selections remain visible on the architecture canvas after reload', async ({ page }) => {
  const chosenNames = ['accounts', 'branches', 'customers', 'loans', 'payments'];
  await page.route('**/api/**', (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/auth/me') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          authenticated: true,
          user: {
            userId: 1,
            username: 'admin',
            displayName: 'Platform Admin',
            roles: ['ADMIN'],
            permissions: ['admin.all'],
            groups: [],
            admin: true
          }
        })
      });
    }
    if (url.pathname === '/api/discovery/graph/2') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          nodes: [
            { id: 'accounts', piiCount: 1 },
            { id: 'branches', piiCount: 0 },
            { id: 'customers', piiCount: 2 },
            { id: 'loans', piiCount: 0 },
            { id: 'payments', piiCount: 0 }
          ],
          edges: [{ id: 'customer-accounts', from: 'customers', to: 'accounts', pkColumn: 'customer_id', fkColumn: 'customer_id' }]
        })
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });
  await page.addInitScript((tables) => {
    localStorage.setItem('forgetdm.entity-architecture.applications', JSON.stringify([{
      id: 'banking-test',
      label: 'Banking architecture',
      dataSourceId: 2,
      dataSourceName: 'sourceDB',
      schema: 'yash',
      tables
    }]));
  }, chosenNames);
  await page.goto('/entity-architecture');
  await expect(page.getByRole('heading', { name: 'Entity Architecture' })).toBeVisible();
  await expect(page.locator('.entity-architecture-application-node')).toHaveCount(1);
  await expect(page.locator('.entity-architecture-table-node')).toHaveCount(5);
  await expect(page.locator('.entity-architecture-application-node')).toBeVisible();
  await expect(page.locator('.entity-architecture-table-node').first()).toBeVisible();
  for (const table of chosenNames) {
    await expect(page.locator('.entity-architecture-table-title').filter({ hasText: new RegExp(`^${table}$`) })).toHaveCount(1);
  }

  await page.reload();
  await expect(page.locator('.entity-architecture-table-node')).toHaveCount(5);
  await expect(page.locator('.entity-architecture-application-node')).toBeVisible();
  await expect(page.locator('.entity-architecture-table-node').first()).toBeVisible();
  const customerNode = page.locator('.entity-architecture-table-node').filter({
    has: page.locator('.entity-architecture-table-title').filter({ hasText: /^customers$/ })
  });
  const accountNode = page.locator('.entity-architecture-table-node').filter({
    has: page.locator('.entity-architecture-table-title').filter({ hasText: /^accounts$/ })
  });
  await customerNode.click();
  await expect(customerNode).toHaveClass(/is-selected/);
  await expect(accountNode).toHaveClass(/is-child/);
});

test('multiple applications create a scrollable workspace and retained cross-database link', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('forgetdm.entity-architecture.applications', JSON.stringify([
      {
        id: 'core-app',
        label: 'Core banking',
        dataSourceId: 2,
        dataSourceName: 'sourceDB',
        schema: 'test',
        tables: ['customers', 'accounts']
      },
      {
        id: 'service-app',
        label: 'Customer service',
        dataSourceId: 4,
        dataSourceName: 'serviceDB',
        schema: 'test1',
        tables: ['customer_service_cases', 'case_activities']
      }
    ]));
    localStorage.setItem('forgetdm.entity-architecture.cross-links', JSON.stringify([{
      id: 'customer-case-link',
      parentSliceId: 'core-app',
      parentTable: 'customers',
      parentColumn: 'customer_id',
      childSliceId: 'service-app',
      childTable: 'customer_service_cases',
      childColumn: 'customer_id',
      kind: 'PARENT_CHILD'
    }]));
  });
  await page.route('**/api/**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/api/auth/me*', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      authenticated: true,
      user: { userId: 1, username: 'admin', displayName: 'Platform Admin', roles: ['ADMIN'], permissions: ['admin.all'], groups: [], admin: true }
    })
  }));
  await page.route('**/api/discovery/graph/2?schema=test', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      nodes: [{ id: 'customers' }, { id: 'accounts' }],
      edges: [{ id: 'customer-account', from: 'customers', to: 'accounts', pkColumn: 'customer_id', fkColumn: 'customer_id' }]
    })
  }));
  await page.route('**/api/discovery/graph/4?schema=test1', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      nodes: [{ id: 'customer_service_cases' }, { id: 'case_activities' }],
      edges: [{ id: 'case-activity', from: 'customer_service_cases', to: 'case_activities', pkColumn: 'case_id', fkColumn: 'case_id' }]
    })
  }));

  await page.goto('/entity-architecture');
  await expect(page.locator('.entity-architecture-application-node')).toHaveCount(2);
  await expect(page.locator('.entity-architecture-edge.is-cross')).toHaveCount(1);
  await expect(page.getByText('1 manual relationship')).toBeVisible();
  const dimensions = await page.locator('.entity-architecture-viewport').evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth
  }));
  expect(dimensions.scrollWidth).toBeGreaterThan(dimensions.clientWidth);

  await page.getByRole('button', { name: 'Expand architecture workspace' }).click();
  await expect(page.locator('.entity-architecture-canvas')).toHaveClass(/is-expanded/);
  await expect(page.getByRole('button', { name: 'Exit expanded workspace' })).toBeVisible();
});

test('tables expand, move, connect across applications, and delete without changing the database', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('forgetdm.entity-architecture.applications', JSON.stringify([
      {
        id: 'core-app',
        label: 'Core banking',
        dataSourceId: 2,
        dataSourceName: 'sourceDB',
        schema: 'test',
        tables: ['customers']
      },
      {
        id: 'service-app',
        label: 'Customer service',
        dataSourceId: 4,
        dataSourceName: 'serviceDB',
        schema: 'test1',
        tables: ['customer_service_cases']
      }
    ]));
    localStorage.setItem('forgetdm.entity-architecture.cross-links', '[]');
  });
  await page.route('**/api/**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/api/auth/me*', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      authenticated: true,
      user: { userId: 1, username: 'admin', displayName: 'Platform Admin', roles: ['ADMIN'], permissions: ['admin.all'], groups: [], admin: true }
    })
  }));
  await page.route('**/api/discovery/graph/2?schema=test', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ nodes: [{ id: 'customers' }], edges: [] })
  }));
  await page.route('**/api/discovery/graph/4?schema=test1', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ nodes: [{ id: 'customer_service_cases' }], edges: [] })
  }));
  await page.route('**/api/datasources/2/tables/customers/columns?schema=test', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { column: 'customer_id', type: 'bigint', nullable: false },
      { column: 'customer_name', type: 'varchar', nullable: false }
    ])
  }));
  await page.route('**/api/datasources/4/tables/customer_service_cases/columns?schema=test1', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { column: 'case_id', type: 'bigint', nullable: false },
      { column: 'customer_id', type: 'bigint', nullable: false },
      { column: 'subject', type: 'varchar', nullable: false }
    ])
  }));

  await page.goto('/entity-architecture');
  const customerNode = page.locator('.entity-architecture-table-node').filter({ hasText: 'customers' });
  const caseNode = page.locator('.entity-architecture-table-node').filter({ hasText: 'customer_service_cases' });
  await page.getByRole('button', { name: 'Expand customers' }).click();
  await page.getByRole('button', { name: 'Expand customer_service_cases' }).click();
  await expect(customerNode.getByText('customer_name', { exact: true })).toBeVisible();
  await expect(caseNode.getByText('subject', { exact: true })).toBeVisible();

  const sourcePort = customerNode.locator('[title="Relationship port: customer_id"].is-output');
  const targetPort = caseNode.locator('[title="Relationship port: customer_id"].is-input');
  const sourceBox = await sourcePort.boundingBox();
  const targetBox = await targetPort.boundingBox();
  expect(sourceBox).not.toBeNull();
  expect(targetBox).not.toBeNull();
  // Users may naturally start from the child/FK port. The canvas must retain
  // the relationship regardless of drag direction.
  await page.mouse.move(targetBox!.x + targetBox!.width / 2, targetBox!.y + targetBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + sourceBox!.height / 2, { steps: 12 });
  await page.mouse.up();
  await expect(page.locator('.entity-architecture-edge.is-cross')).toHaveCount(1);
  await expect.poll(async () => page.evaluate(() => JSON.parse(localStorage.getItem('forgetdm.entity-architecture.cross-links') || '[]').length)).toBe(1);

  const header = caseNode.locator('.entity-architecture-flow-table-head');
  const before = await caseNode.boundingBox();
  const headerBox = await header.boundingBox();
  expect(before).not.toBeNull();
  expect(headerBox).not.toBeNull();
  await page.mouse.move(headerBox!.x + 80, headerBox!.y + 18);
  await page.mouse.down();
  await page.mouse.move(headerBox!.x + 150, headerBox!.y + 70, { steps: 8 });
  await page.mouse.up();
  const after = await caseNode.boundingBox();
  expect(after!.x).toBeGreaterThan(before!.x + 30);
  await expect.poll(async () => page.evaluate(() => Object.keys(JSON.parse(localStorage.getItem('forgetdm.entity-architecture.positions') || '{}')).length)).toBeGreaterThan(0);

  await page.locator('.entity-architecture-edge.is-cross .react-flow__edge-interaction').click({ force: true });
  await page.getByRole('button', { name: 'Delete selected link' }).click();
  await expect(page.locator('.entity-architecture-edge.is-cross')).toHaveCount(0);
  await expect.poll(async () => page.evaluate(() => JSON.parse(localStorage.getItem('forgetdm.entity-architecture.cross-links') || '[]').length)).toBe(0);

  await caseNode.getByRole('button', { name: 'Remove customer_service_cases' }).click();
  await expect(page.locator('.entity-architecture-table-node')).toHaveCount(1);
  await expect.poll(async () => page.evaluate(() => JSON.parse(localStorage.getItem('forgetdm.entity-architecture.applications') || '[]').length)).toBe(1);
});
