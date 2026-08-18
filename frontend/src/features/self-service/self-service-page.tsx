'use client';

import { useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Code, CopyButton, Group, Loader, Modal, MultiSelect, NumberInput,
  Paper, Progress, ScrollArea, Select, SimpleGrid, Stack, Stepper, Switch, Tabs, Text, Textarea,
  TextInput, ThemeIcon, Timeline, Title
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconActivity, IconAdjustments, IconCalendar, IconCheck, IconCode,
  IconDatabase, IconDownload, IconExternalLink, IconPlayerPlay, IconSearch, IconSend,
  IconShieldCheck, IconSparkles, IconX
} from '@tabler/icons-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';

type Product = {
  id: string; productType: string; artifactId: string; artifactVersion?: number | null; label: string;
  description?: string | null; category?: string | null; tags?: string[]; ownerUsername?: string;
  approvalMode: string; questionnaire?: { fields?: QuestionField[] }; guardrails?: Record<string, unknown>;
  capabilities?: ProductCapabilities;
  allowedEnvironments?: string[]; deliveryInstructions?: string | null; enabled?: boolean; updatedAt?: string;
};
type ProductCapabilities = {
  supportsVolume?: boolean; supportsVariety?: boolean; supportsReservation?: boolean; supportsLaunchWindow?: boolean;
  supportsPointInTime?: boolean; supportsRewind?: boolean; supportsRefresh?: boolean; deliveryModes?: string[];
  systems?: string[]; lockedControls?: string[]; outcome?: string;
};
type QuestionField = { key: string; label: string; type?: 'TEXT' | 'NUMBER' | 'SELECT' | 'BOOLEAN'; required?: boolean; options?: string[]; placeholder?: string };
type OrderEvent = { eventType: string; actor: string; message?: string; createdAt?: string };
type Order = {
  id: string; productId: string; productType: string; artifactId: string; productLabel: string;
  requestedById: number; requestedBy: string; purpose: string; testType?: string | null; environment?: string | null;
  parametersJson?: string; requestedVolume?: number | null; requestedVariety?: string | null; deliveryMode?: string | null;
  reservationRequested?: boolean; reservationHours?: number | null; scheduleAt?: string | null; status: string;
  decisionBy?: string | null; decisionNote?: string | null; runType?: string | null; runRef?: string | null;
  resultJson?: string | null; createdAt?: string; decidedAt?: string | null; fulfilledAt?: string | null; events?: OrderEvent[];
};
type Candidate = { productType: string; artifactId: string; name: string; description?: string };
type Metrics = { visibleRequests: number; statusCounts: Record<string, number>; averageFulfillmentSeconds: number; scope: string };
type AuthMe = { authenticated?: boolean; user?: { userId?: number; username?: string; roles?: string[] } };
type Runner = { requestId: string; product: string; launchCommand: string; statusCommand: string; note: string };
type ExecutionLog = { at?: string | null; level: string; label: string; message: string };
type ExecutionView = {
  requestId: string; runType?: string | null; runRef?: string | null; status: string; stage: string;
  progress: number; message: string; rowsRead: number; rowsWritten: number; rowsRejected: number;
  rowsTotal?: number; currentTable?: string | null; partitionCount?: number;
  startedAt?: string | null; finishedAt?: string | null; logs: ExecutionLog[]; modulePath?: string | null;
};

const TEST_TYPES = ['UNIT', 'FUNCTIONAL', 'INTEGRATION', 'API', 'REGRESSION', 'PERFORMANCE', 'NEGATIVE', 'TRAINING'];
const DEFAULT_ENVIRONMENTS = ['DEV', 'QA', 'UAT', 'PERFORMANCE', 'TRAINING'];

export function SelfServicePage() {
  const queryClient = useQueryClient();
  const { can, isAdmin, ready } = usePermissions();
  const canRead = can('provision.read');
  const canRequest = can('provision.run');
  const canApprove = can('provision.approve');
  const canManage = can('datascope.manage');
  const meQuery = useQuery({ queryKey: keys.auth.me, queryFn: () => apiFetch<AuthMe>('/api/auth/me') });
  const catalogQuery = useQuery({ queryKey: keys.selfService.enterpriseCatalog, queryFn: () => apiFetch<Product[]>('/api/self-service/v2/catalog'), enabled: canRead });
  const ordersQuery = useQuery({ queryKey: keys.selfService.enterpriseOrders, queryFn: () => apiFetch<Order[]>('/api/self-service/v2/orders'), enabled: canRead, refetchInterval: 8000 });
  const metricsQuery = useQuery({ queryKey: keys.selfService.enterpriseMetrics, queryFn: () => apiFetch<Metrics>('/api/self-service/v2/metrics'), enabled: canRead, refetchInterval: 15000 });
  const candidatesQuery = useQuery({ queryKey: keys.selfService.enterpriseCandidates, queryFn: () => apiFetch<Candidate[]>('/api/self-service/v2/candidates'), enabled: canManage });
  const productsQuery = useQuery({ queryKey: keys.selfService.enterpriseProducts, queryFn: () => apiFetch<Product[]>('/api/self-service/v2/products'), enabled: canManage });
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [requestProduct, setRequestProduct] = useState<Product | null>(null);
  const [requestDraft, setRequestDraft] = useState(() => emptyRequest());
  const [action, setAction] = useState<{ order: Order; kind: 'approve' | 'reject' | 'cancel'; note: string } | null>(null);
  const [detail, setDetail] = useState<Order | null>(null);
  const [liveOrder, setLiveOrder] = useState<Order | null>(null);
  const [comment, setComment] = useState('');
  const [runner, setRunner] = useState<Runner | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [publishOpened, setPublishOpened] = useState(false);
  const [publishDraft, setPublishDraft] = useState(() => emptyPublish());
  const executionQuery = useQuery({
    queryKey: ['self-service', 'execution', liveOrder?.id],
    queryFn: () => apiFetch<ExecutionView>(`/api/self-service/v2/orders/${liveOrder?.id}/execution`),
    enabled: Boolean(liveOrder?.id),
    refetchInterval: (query) => executionLive((query.state.data as ExecutionView | undefined)?.status) ? 2000 : false
  });

  const catalog = useMemo(() => (canRead ? catalogQuery.data || [] : []).filter((product) => {
    const q = search.trim().toLowerCase();
    return (!typeFilter || product.productType === typeFilter) && (!q || `${product.label} ${product.description || ''} ${(product.tags || []).join(' ')}`.toLowerCase().includes(q));
  }), [canRead, catalogQuery.data, search, typeFilter]);
  const orders = canRead ? ordersQuery.data || [] : [];
  const pending = orders.filter((order) => order.status === 'PENDING_APPROVAL');
  const myUsername = meQuery.data?.user?.username || '';
  const productTypes = [...new Set((catalogQuery.data || []).map((product) => product.productType))];

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseCatalog }),
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseOrders }),
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseMetrics }),
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseProducts })
    ]);
  };

  const openRequest = (product: Product) => {
    if (!canRequest) return;
    const parameters = Object.fromEntries(fieldsFor(product).map((field) => [field.key, field.type === 'BOOLEAN' ? false : '']));
    const capabilities = capabilitiesFor(product);
    setRequestDraft({
      ...emptyRequest(),
      environment: product.allowedEnvironments?.[0] || 'QA',
      deliveryMode: capabilities.deliveryModes[0] || 'DATABASE',
      parameters
    });
    setRequestProduct(product);
  };

  const submitRequest = async () => {
    if (!canRequest || !requestProduct || !requestDraft.purpose.trim()) return;
    setBusy('request');
    try {
      const created = await apiPost<Order>('/api/self-service/v2/orders', {
        productId: requestProduct.id, purpose: requestDraft.purpose.trim(), testType: requestDraft.testType,
        environment: requestDraft.environment, parameters: requestDraft.parameters,
        requestedVolume: capabilitiesFor(requestProduct).supportsVolume ? requestDraft.volume || null : null,
        requestedVariety: capabilitiesFor(requestProduct).supportsVariety ? requestDraft.variety || null : null,
        deliveryMode: requestDraft.deliveryMode,
        reservationRequested: capabilitiesFor(requestProduct).supportsReservation && requestDraft.reserve,
        reservationHours: capabilitiesFor(requestProduct).supportsReservation && requestDraft.reserve ? requestDraft.reservationHours : null,
        scheduleAt: capabilitiesFor(requestProduct).supportsLaunchWindow && requestDraft.scheduleAt
          ? new Date(requestDraft.scheduleAt).toISOString()
          : null
      });
      notifications.show({
        color: 'green',
        title: 'Request recorded',
        message: created.status === 'APPROVED' ? 'Approved and ready to launch.' : 'Sent through maker-checker review.'
      });
      setRequestProduct(null); await refresh();
    } catch (error) { notifyError('Request could not be submitted', error); }
    finally { setBusy(null); }
  };

  const decide = async () => {
    if (!action?.note.trim()) return;
    if (action.kind === 'cancel' ? !canRequest : !canApprove) return;
    setBusy(`action:${action.order.id}`);
    try {
      const path = action.kind === 'cancel' ? 'cancel' : `decision/${action.kind}`;
      await apiPost(`/api/self-service/v2/orders/${action.order.id}/${path}`, action.kind === 'cancel' ? { message: action.note.trim() } : { note: action.note.trim() });
      notifications.show({ color: action.kind === 'approve' ? 'green' : 'yellow', title: action.kind === 'approve' ? 'Approved' : action.kind === 'reject' ? 'Rejected' : 'Canceled', message: action.order.productLabel });
      setAction(null); await refresh();
    } catch (error) { notifyError('Request action failed', error); }
    finally { setBusy(null); }
  };

  const fulfill = async (order: Order) => {
    if (!canRequest) return;
    setBusy(`fulfill:${order.id}`);
    try {
      const result = await apiPost<Order>(`/api/self-service/v2/orders/${order.id}/fulfill`, {});
      notifications.show({ color: 'green', title: 'Execution submitted', message: result.runRef ? `${result.runType} run ${result.runRef}` : result.productLabel });
      setLiveOrder(result);
      await refresh();
    } catch (error) { notifyError('Execution could not be launched', error); }
    finally { setBusy(null); }
  };

  const openDetail = async (order: Order) => {
    if (!canRead) return;
    try { setDetail(await apiFetch<Order>(`/api/self-service/v2/orders/${order.id}`)); }
    catch (error) { notifyError('Request details could not be loaded', error); }
  };

  const addComment = async () => {
    if (!canRequest || !detail || !comment.trim()) return;
    try { const next = await apiPost<Order>(`/api/self-service/v2/orders/${detail.id}/comments`, { message: comment.trim() }); setDetail(next); setComment(''); await refresh(); }
    catch (error) { notifyError('Comment could not be added', error); }
  };

  const openRunner = async (order: Order) => {
    if (!canRead) return;
    try { setRunner(await apiFetch<Runner>(`/api/self-service/v2/orders/${order.id}/runner`)); }
    catch (error) { notifyError('Runner instructions could not be generated', error); }
  };

  const publish = async () => {
    if (!canManage) return;
    const candidate = (candidatesQuery.data || []).find((item) => candidateKey(item) === publishDraft.candidateKey);
    if (!candidate || !publishDraft.label.trim()) return;
    setBusy('publish');
    try {
      await apiPost('/api/self-service/v2/products', {
        productType: candidate.productType, artifactId: candidate.artifactId, label: publishDraft.label.trim(),
        description: publishDraft.description.trim(), category: publishDraft.category.trim(), tags: publishDraft.tags.trim(),
        enabled: true, approvalMode: publishDraft.approvalMode,
        questionnaire: { fields: questionnaireFor(candidate.productType) },
        guardrails: candidate.productType === 'DATASCOPE'
          ? { maxVolume: publishDraft.maxVolume || null, allowScheduling: true }
          : { allowScheduling: true },
        allowedEnvironments: publishDraft.environments, deliveryInstructions: publishDraft.instructions.trim()
      });
      setPublishOpened(false); setPublishDraft(emptyPublish()); await refresh();
      notifications.show({ color: 'green', title: 'Catalog product published', message: publishDraft.label });
    } catch (error) { notifyError('Product could not be published', error); }
    finally { setBusy(null); }
  };

  const toggleProduct = async (product: Product, enabled: boolean) => {
    if (!canManage) return;
    await apiPost(`/api/self-service/v2/products/${product.id}/${enabled ? 'enable' : 'disable'}`, {});
    await refresh();
  };

  if (!ready) return <main className="forge-page selfx-page"><Paper p="lg"><Text c="dimmed">Checking Self-Service access...</Text></Paper></main>;
  if (!canRead && !canManage) return <main className="forge-page selfx-page"><Alert color="yellow" title="Self-Service is not available">Your role does not include access to governed data products.</Alert></main>;

  return (
    <main className="forge-page selfx-page">
      <Stack gap="lg">
        <Group justify="space-between" align="flex-start">
          <div><Text className="forge-eyebrow">Governed data products</Text><Title order={1}>Self-Service</Title><Text c="dimmed" maw={820}>Find an approved data product, answer only its safe questionnaire, and deliver repeatable test data without exposing source schemas, policies, or credentials.</Text></div>
          {canManage ? <Button leftSection={<IconAdjustments size={16} />} onClick={() => setPublishOpened(true)}>Publish product</Button> : null}
        </Group>

        {canRead ? <MetricsStrip metrics={metricsQuery.data} /> : null}
        {canRead ? <QueryErrorBanner errors={[catalogQuery.error, ordersQuery.error, metricsQuery.error]} onRetry={() => Promise.all([catalogQuery.refetch(), ordersQuery.refetch(), metricsQuery.refetch()])} title="Self-service workspace could not be loaded" /> : null}

        <Tabs defaultValue={canRead ? 'catalog' : 'manage'} className="selfx-tabs">
          <Tabs.List>
            {canRead ? <Tabs.Tab value="catalog" leftSection={<IconSearch size={15} />}>Product catalog ({catalogQuery.data?.length || 0})</Tabs.Tab> : null}
            {canRead ? <Tabs.Tab value="requests" leftSection={<IconActivity size={15} />}>My requests ({orders.filter((order) => order.requestedBy === myUsername).length})</Tabs.Tab> : null}
            {canRead && canApprove ? <Tabs.Tab value="approvals" leftSection={<IconShieldCheck size={15} />}>Approvals ({pending.length})</Tabs.Tab> : null}
            {canManage ? <Tabs.Tab value="manage" leftSection={<IconAdjustments size={15} />}>Catalog management</Tabs.Tab> : null}
          </Tabs.List>

          {canRead ? <Tabs.Panel value="catalog" pt="md">
            <Group mb="md" align="flex-end"><TextInput label="Search products" placeholder="Customer, cards, regression, reservation..." leftSection={<IconSearch size={15} />} value={search} onChange={(event) => setSearch(event.currentTarget.value)} style={{ flex: 1 }} /><Select label="Product type" clearable value={typeFilter} onChange={setTypeFilter} data={productTypes} w={220} /></Group>
            {catalogQuery.isLoading ? <Loader size="sm" /> : catalog.length ? <SimpleGrid cols={{ base: 1, md: 2, xl: 3 }}>{catalog.map((product) => <ProductCard key={product.id} product={product} canRequest={canRequest} isAdmin={isAdmin} onRequest={() => openRequest(product)} />)}</SimpleGrid> : <Alert color="blue">No products match this search. Catalog managers can publish approved DataScope, synthetic, mapping, reservation, or virtual-data products.</Alert>}
          </Tabs.Panel> : null}

          {canRead ? <Tabs.Panel value="requests" pt="md"><OrderList orders={orders.filter((order) => order.requestedBy === myUsername)} username={myUsername} canApprove={false} canRun={canRequest} busy={busy} onAction={setAction} onFulfill={fulfill} onDetail={openDetail} onRunner={openRunner} onLive={setLiveOrder} /></Tabs.Panel> : null}
          {canRead && canApprove ? <Tabs.Panel value="approvals" pt="md">{pending.length ? <OrderList orders={pending} username={myUsername} canApprove canRun={canRequest} busy={busy} onAction={setAction} onFulfill={fulfill} onDetail={openDetail} onRunner={openRunner} onLive={setLiveOrder} /> : <Alert color="blue" title="No requests need your approval">Requests created by an administrator are approved automatically and appear under My requests, ready to launch. This queue only contains requests submitted by other users.</Alert>}</Tabs.Panel> : null}
          {canManage ? <Tabs.Panel value="manage" pt="md"><CatalogManagement products={productsQuery.data || []} onToggle={toggleProduct} onPublish={() => setPublishOpened(true)} /></Tabs.Panel> : null}
        </Tabs>
      </Stack>

      <RequestModal key={requestProduct?.id || 'closed'} product={canRequest ? requestProduct : null} draft={requestDraft} setDraft={setRequestDraft} busy={busy === 'request'} isAdmin={isAdmin} onClose={() => setRequestProduct(null)} onSubmit={submitRequest} />
      <ActionModal action={action && (action.kind === 'cancel' ? canRequest : canApprove) ? action : null} setAction={setAction} busy={busy?.startsWith('action:') || false} onSubmit={decide} />
      <DetailModal order={detail} comment={comment} setComment={setComment} canComment={canRequest} onComment={addComment} onClose={() => setDetail(null)} />
      <LiveExecutionModal order={liveOrder} execution={executionQuery.data} loading={executionQuery.isLoading} error={executionQuery.error} onRetry={() => void executionQuery.refetch()} onClose={() => setLiveOrder(null)} />
      <RunnerModal runner={runner} onClose={() => setRunner(null)} />
      <PublishModal opened={canManage && publishOpened} onClose={() => setPublishOpened(false)} candidates={candidatesQuery.data || []} draft={publishDraft} setDraft={setPublishDraft} busy={busy === 'publish'} onSubmit={publish} />
    </main>
  );
}

function MetricsStrip({ metrics }: { metrics?: Metrics }) {
  const status = metrics?.statusCounts || {};
  const complete = (status.COMPLETED || 0) + (status.FULFILLED || 0); const total = metrics?.visibleRequests || 0;
  const cards = [
    ['Visible requests', total, metrics?.scope === 'TEAM' ? 'Team workload' : 'Your workload'],
    ['Awaiting approval', status.PENDING_APPROVAL || 0, 'Maker-checker queue'],
    ['Ready to launch', status.APPROVED || 0, 'Approved and governed'],
    ['Fulfilled', complete, metrics?.averageFulfillmentSeconds ? `Average ${duration(metrics.averageFulfillmentSeconds)}` : 'No completed timing yet']
  ];
  return <SimpleGrid cols={{ base: 2, lg: 4 }}>{cards.map(([label, value, note]) => <Paper key={String(label)} className="forge-card selfx-metric" p="sm"><Text size="xs" c="dimmed" fw={750}>{label}</Text><Text size="xl" fw={850}>{value}</Text><Text size="xs" c="dimmed">{note}</Text></Paper>)}</SimpleGrid>;
}

function ProductCard({ product, canRequest, isAdmin, onRequest }: { product: Product; canRequest: boolean; isAdmin: boolean; onRequest: () => void }) {
  const capabilities = capabilitiesFor(product);
  return <Paper className="forge-card selfx-product" p="md"><Stack gap="sm"><Group justify="space-between" align="flex-start"><div className="selfx-product-icon">{productIcon(product.productType)}</div><Badge variant="light" color={typeColor(product.productType)}>{typeLabel(product.productType)}</Badge></Group><div><Text fw={850}>{product.label}</Text><Text size="sm" c="dimmed" lineClamp={2}>{product.description || 'Governed reusable test data product'}</Text></div><Text size="sm" fw={700}>{capabilities.outcome}</Text><Group gap={5}>{(capabilities.systems.length ? capabilities.systems : product.tags || []).slice(0, 4).map((tag) => <Badge key={tag} size="xs" variant="outline" color="gray">{tag}</Badge>)}</Group><div className="selfx-product-meta"><span>{product.category || 'General'}</span><span>{isAdmin || product.approvalMode === 'NONE' ? 'Ready immediately' : 'Maker-checker'}</span></div><Group justify="space-between"><Text size="xs" c="dimmed">{(product.allowedEnvironments || []).join(', ') || 'Published environments'}</Text>{canRequest ? <Button size="xs" leftSection={<IconSend size={14} />} onClick={onRequest}>Start request</Button> : null}</Group></Stack></Paper>;
}

function OrderList({ orders, username, canApprove, canRun, busy, onAction, onFulfill, onDetail, onRunner, onLive }: { orders: Order[]; username: string; canApprove: boolean; canRun: boolean; busy: string | null; onAction: (value: { order: Order; kind: 'approve' | 'reject' | 'cancel'; note: string }) => void; onFulfill: (order: Order) => void; onDetail: (order: Order) => void; onRunner: (order: Order) => void; onLive: (order: Order) => void }) {
  if (!orders.length) return <Alert color="blue">No requests in this view.</Alert>;
  return <Stack gap="sm">{orders.map((order) => <Paper key={order.id} className="forge-card selfx-order" p="md"><Group justify="space-between" align="flex-start" wrap="nowrap"><div className="selfx-order-main"><Group gap="xs"><Text fw={850}>{order.productLabel}</Text><Badge color={statusColor(order.status)} variant="light">{order.status.replaceAll('_', ' ')}</Badge><Badge variant="outline" color="gray">{typeLabel(order.productType)}</Badge></Group><Text size="sm" mt={5}>{order.purpose}</Text><Text size="xs" c="dimmed" mt={5}>{order.requestedBy} · {order.environment || 'Default environment'} · {formatWhen(order.createdAt)}{order.scheduleAt ? ` · scheduled ${formatWhen(order.scheduleAt)}` : ''}</Text>{order.decisionNote ? <Text size="xs" c="dimmed" mt={3}>Decision by {order.decisionBy}: {order.decisionNote}</Text> : null}{order.runRef ? <Text size="xs" fw={750} mt={3}>{order.runType} execution {order.runRef}</Text> : null}</div><Group gap="xs" justify="flex-end">{canApprove && order.status === 'PENDING_APPROVAL' && order.requestedBy !== username ? <><Button size="xs" color="green" leftSection={<IconCheck size={13} />} onClick={() => onAction({ order, kind: 'approve', note: '' })}>Approve</Button><Button size="xs" color="red" variant="light" leftSection={<IconX size={13} />} onClick={() => onAction({ order, kind: 'reject', note: '' })}>Reject</Button></> : null}{canRun && order.status === 'APPROVED' && order.requestedBy === username ? <Button size="xs" leftSection={<IconPlayerPlay size={13} />} loading={busy === `fulfill:${order.id}`} onClick={() => void onFulfill(order)}>Launch</Button> : null}{canRun && ['PENDING_APPROVAL', 'APPROVED'].includes(order.status) && order.requestedBy === username ? <Button size="xs" variant="subtle" color="red" onClick={() => onAction({ order, kind: 'cancel', note: '' })}>Cancel</Button> : null}{order.runRef ? <Button size="xs" variant="light" leftSection={<IconActivity size={13} />} onClick={() => onLive(order)}>View live run</Button> : null}<Button size="xs" variant="default" onClick={() => void onDetail(order)}>Activity</Button><Button size="xs" variant="subtle" leftSection={<IconCode size={13} />} onClick={() => void onRunner(order)}>API</Button></Group></Group></Paper>)}</Stack>;
}

type RequestDraft = ReturnType<typeof emptyRequest>;
function RequestModal({ product, draft, setDraft, busy, isAdmin, onClose, onSubmit }: { product: Product | null; draft: RequestDraft; setDraft: (value: RequestDraft) => void; busy: boolean; isAdmin: boolean; onClose: () => void; onSubmit: () => void }) {
  const [step, setStep] = useState(0);
  if (!product) return null;
  const fields = fieldsFor(product);
  const capabilities = capabilitiesFor(product);
  const missing = fields.some((field) => field.required && String(draft.parameters[field.key] ?? '').trim() === '');
  const scenarioReady = draft.purpose.trim().length >= 8 && Boolean(draft.testType);
  const deliveryReady = Boolean(draft.environment) && Boolean(draft.deliveryMode)
    && (!capabilities.supportsVolume || draft.volume === '' || Number(draft.volume) > 0);
  const canContinue = step === 0 ? scenarioReady : step === 1 ? !missing : step === 2 ? deliveryReady : true;
  const approvalLabel = isAdmin ? 'Administrator policy: no approval wait' : product.approvalMode === 'NONE' ? 'Pre-approved product policy' : 'Maker-checker approval';

  return <Modal opened onClose={onClose} fullScreen title="Business-scenario request" classNames={{ body: 'selfx-wizard-body' }}>
    <div className="selfx-wizard-shell">
      <div className="selfx-wizard-head">
        <Group justify="space-between" align="flex-start">
          <div><Text className="forge-eyebrow">{typeLabel(product.productType)}</Text><Title order={2}>{product.label}</Title><Text c="dimmed">Describe the test need; the catalog product owns the technical implementation.</Text></div>
          <Badge size="lg" variant="light" color={isAdmin || product.approvalMode === 'NONE' ? 'green' : 'blue'}>{approvalLabel}</Badge>
        </Group>
        <Stepper active={step} onStepClick={(next) => next < step && setStep(next)} allowNextStepsSelect={false} mt="lg">
          <Stepper.Step label="Scenario" description="What the tester needs" />
          <Stepper.Step label="Conditions" description="Allowed business inputs" />
          <Stepper.Step label="Delivery" description="Where and when" />
          <Stepper.Step label="Review" description="Exact execution flow" />
        </Stepper>
      </div>

      <div className="selfx-wizard-content">
        <section className="selfx-wizard-main">
          {step === 0 ? <Stack gap="lg">
            <div><Title order={3}>Define the business scenario</Title><Text c="dimmed">State the outcome and test behavior, without production values or technical connection details.</Text></div>
            <Textarea label="Test objective / business purpose" description="Minimum 8 characters; retained as audit evidence." minRows={6} value={draft.purpose} onChange={(event) => setDraft({ ...draft, purpose: event.currentTarget.value })} placeholder="Validate card replacement while the customer, account, limits, and payment history remain consistent across the selected systems." />
            <Select label="Test type" data={TEST_TYPES} value={draft.testType} onChange={(value) => setDraft({ ...draft, testType: value || 'FUNCTIONAL' })} maw={420} />
          </Stack> : null}

          {step === 1 ? <Stack gap="lg">
            <div><Title order={3}>Set permitted business conditions</Title><Text c="dimmed">Only inputs published by the product owner are available. They are validated again by the backend.</Text></div>
            {fields.length ? <SimpleGrid cols={{ base: 1, md: 2 }}>{fields.map((field) => <QuestionInput key={field.key} field={field} value={draft.parameters[field.key]} onChange={(value) => setDraft({ ...draft, parameters: { ...draft.parameters, [field.key]: value } })} />)}</SimpleGrid> : <Alert color="blue" title="No variable conditions">This product runs its approved design as published. Source selection, masking, relationships, row design, and receiver settings remain locked.</Alert>}
            {capabilities.supportsVariety ? <TextInput label="Required variation / edge cases" value={draft.variety} onChange={(event) => setDraft({ ...draft, variety: event.currentTarget.value })} placeholder="Boundary, negative, rare states" /> : null}
          </Stack> : null}

          {step === 2 ? <Stack gap="lg">
            <div><Title order={3}>Choose the delivery contract</Title><Text c="dimmed">The product exposes only delivery controls its execution engine supports.</Text></div>
            <SimpleGrid cols={{ base: 1, md: 2 }}>
              <Select label="Target environment" data={product.allowedEnvironments?.length ? product.allowedEnvironments : DEFAULT_ENVIRONMENTS} value={draft.environment} onChange={(value) => setDraft({ ...draft, environment: value || '' })} />
              <Select label="Delivery mode" data={capabilities.deliveryModes.map((mode) => ({ value: mode, label: typeLabel(mode) }))} value={draft.deliveryMode} onChange={(value) => setDraft({ ...draft, deliveryMode: value || capabilities.deliveryModes[0] })} disabled={capabilities.deliveryModes.length === 1} />
              {capabilities.supportsVolume ? <NumberInput label="Maximum driver rows" description="Optional subset cap; relationships may add dependent rows." min={1} max={Number(product.guardrails?.maxVolume || Number.MAX_SAFE_INTEGER)} value={draft.volume} onChange={(value) => setDraft({ ...draft, volume: typeof value === 'number' ? value : '' })} placeholder="Product default" /> : null}
              {capabilities.supportsLaunchWindow ? <TextInput type="datetime-local" label="Do not launch before" description="This is a launch guard, not a recurring schedule." leftSection={<IconCalendar size={14} />} value={draft.scheduleAt} onChange={(event) => setDraft({ ...draft, scheduleAt: event.currentTarget.value })} /> : null}
            </SimpleGrid>
            {capabilities.supportsReservation ? <Group><Switch label="Reserve delivered data" checked={draft.reserve} onChange={(event) => setDraft({ ...draft, reserve: event.currentTarget.checked })} /><NumberInput label="Reservation hours" min={1} max={Number(product.guardrails?.maxReservationHours || 168)} disabled={!draft.reserve} value={draft.reservationHours} onChange={(value) => setDraft({ ...draft, reservationHours: typeof value === 'number' ? value : 24 })} /></Group> : null}
            {product.deliveryInstructions ? <Alert color="gray" title="Delivery notes">{product.deliveryInstructions}</Alert> : null}
          </Stack> : null}

          {step === 3 ? <Stack gap="lg">
            <div><Title order={3}>Review the executable request</Title><Text c="dimmed">This is the flow ForgeTDM will submit. No hidden source, policy, or target choices are added here.</Text></div>
            <div className="selfx-review-flow">
              <ReviewStep number={1} title="Resolve product" detail={product.label} />
              <ReviewStep number={2} title="Apply conditions" detail={fields.length ? `${fields.length} published input${fields.length === 1 ? '' : 's'}` : 'Approved design unchanged'} />
              <ReviewStep number={3} title="Protect and deliver" detail={`${capabilities.outcome} to ${draft.environment}`} />
              <ReviewStep number={4} title="Govern execution" detail={approvalLabel} />
            </div>
            <SimpleGrid cols={{ base: 1, md: 2 }}>
              <Paper className="forge-card" p="md"><Text size="xs" fw={800} c="dimmed">TEST OBJECTIVE</Text><Text mt={6}>{draft.purpose}</Text><Group gap="xs" mt="sm"><Badge variant="light">{draft.testType}</Badge><Badge variant="light">{draft.environment}</Badge><Badge variant="light">{typeLabel(draft.deliveryMode)}</Badge></Group></Paper>
              <Paper className="forge-card" p="md"><Text size="xs" fw={800} c="dimmed">LOCKED BY PRODUCT OWNER</Text><Stack gap={7} mt="sm">{capabilities.lockedControls.map((control) => <Group key={control} gap="xs"><IconShieldCheck size={15} color="var(--forge-green)" /><Text size="sm">{control}</Text></Group>)}</Stack></Paper>
            </SimpleGrid>
          </Stack> : null}
        </section>

        <aside className="selfx-wizard-aside">
          <Text size="xs" fw={850} c="dimmed">DELIVERED OUTCOME</Text><Text fw={850} mt={5}>{capabilities.outcome}</Text>
          <Text size="xs" fw={850} c="dimmed" mt="lg">SYSTEMS IN SCOPE</Text><Group gap={6} mt={7}>{(capabilities.systems.length ? capabilities.systems : ['Defined by product']).map((system) => <Badge key={system} variant="light" color="gray">{system}</Badge>)}</Group>
          <Text size="xs" fw={850} c="dimmed" mt="lg">AVAILABLE CONTROLS</Text><Stack gap={7} mt={7}><CapabilityLine enabled={capabilities.supportsVolume} label="Volume cap" /><CapabilityLine enabled={fields.length > 0} label="Business conditions" /><CapabilityLine enabled={capabilities.supportsPointInTime} label="Point in time" /><CapabilityLine enabled={capabilities.supportsRefresh} label="Refresh" /><CapabilityLine enabled={capabilities.supportsRewind} label="Rewind" /></Stack>
        </aside>
      </div>

      <Group className="selfx-wizard-actions" justify="space-between">
        <Button variant="subtle" color="gray" onClick={onClose}>Discard request</Button>
        <Group><Button variant="default" disabled={step === 0} onClick={() => setStep((value) => Math.max(0, value - 1))}>Back</Button>{step < 3 ? <Button disabled={!canContinue} onClick={() => setStep((value) => Math.min(3, value + 1))}>Continue</Button> : <Button loading={busy} disabled={!scenarioReady || missing || !deliveryReady} onClick={onSubmit}>{isAdmin || product.approvalMode === 'NONE' ? 'Create approved request' : 'Submit for approval'}</Button>}</Group>
      </Group>
    </div>
  </Modal>;
}

function ReviewStep({ number, title, detail }: { number: number; title: string; detail: string }) {
  return <div><ThemeIcon radius="xl" size={30} variant="light">{number}</ThemeIcon><span><Text fw={800}>{title}</Text><Text size="xs" c="dimmed">{detail}</Text></span></div>;
}

function CapabilityLine({ enabled, label }: { enabled: boolean; label: string }) {
  return <Group gap="xs"><ThemeIcon size={22} radius="xl" color={enabled ? 'green' : 'gray'} variant="light">{enabled ? <IconCheck size={13} /> : <IconX size={13} />}</ThemeIcon><Text size="sm" c={enabled ? undefined : 'dimmed'}>{label}</Text></Group>;
}

function QuestionInput({ field, value, onChange }: { field: QuestionField; value: unknown; onChange: (value: unknown) => void }) {
  if (field.type === 'BOOLEAN') return <Switch label={field.label} checked={Boolean(value)} onChange={(event) => onChange(event.currentTarget.checked)} />;
  if (field.type === 'NUMBER') return <NumberInput label={field.label} required={field.required} value={typeof value === 'number' ? value : String(value || '')} onChange={onChange} placeholder={field.placeholder} />;
  if (field.type === 'SELECT') return <Select label={field.label} required={field.required} searchable data={field.options || []} value={String(value || '') || null} onChange={(next) => onChange(next || '')} placeholder={field.placeholder} />;
  return <TextInput label={field.label} required={field.required} value={String(value || '')} onChange={(event) => onChange(event.currentTarget.value)} placeholder={field.placeholder} spellCheck={false} />;
}

function ActionModal({ action, setAction, busy, onSubmit }: { action: { order: Order; kind: 'approve' | 'reject' | 'cancel'; note: string } | null; setAction: (value: typeof action) => void; busy: boolean; onSubmit: () => void }) {
  return <Modal opened={Boolean(action)} onClose={() => setAction(null)} title={action ? `${action.kind[0].toUpperCase()}${action.kind.slice(1)} ${action.order.productLabel}` : ''}><Stack><Text size="sm">This decision becomes part of the immutable request activity trail.</Text><Textarea label={action?.kind === 'approve' ? 'Approval note / e-signature reason' : action?.kind === 'reject' ? 'Rejection reason' : 'Cancellation reason'} minRows={3} value={action?.note || ''} onChange={(event) => action && setAction({ ...action, note: event.currentTarget.value })} /><Group justify="flex-end"><Button variant="default" onClick={() => setAction(null)}>Back</Button><Button color={action?.kind === 'approve' ? 'green' : 'red'} loading={busy} disabled={!action?.note.trim()} onClick={onSubmit}>Confirm {action?.kind}</Button></Group></Stack></Modal>;
}

function DetailModal({ order, comment, setComment, canComment, onComment, onClose }: { order: Order | null; comment: string; setComment: (value: string) => void; canComment: boolean; onComment: () => void; onClose: () => void }) {
  return <Modal opened={Boolean(order)} onClose={onClose} title={order?.productLabel || 'Request activity'} size="lg"><Stack><Group justify="space-between"><Badge color={statusColor(order?.status || '')}>{order?.status.replaceAll('_', ' ')}</Badge>{order?.runRef ? <Code>{order.runType}:{order.runRef}</Code> : null}</Group><Text size="sm">{order?.purpose}</Text><Timeline active={(order?.events || []).length} bulletSize={20} lineWidth={2}>{(order?.events || []).map((event, index) => <Timeline.Item key={`${event.createdAt}-${index}`} title={event.eventType.replaceAll('_', ' ')} bullet={<IconActivity size={11} />}><Text size="sm">{event.message || 'Status updated'}</Text><Text size="xs" c="dimmed">{event.actor} · {formatWhen(event.createdAt)}</Text></Timeline.Item>)}</Timeline>{canComment ? <Group align="flex-end"><Textarea label="Add request comment" value={comment} onChange={(event) => setComment(event.currentTarget.value)} minRows={2} style={{ flex: 1 }} /><Button disabled={!comment.trim()} onClick={onComment}>Add</Button></Group> : null}</Stack></Modal>;
}

function LiveExecutionModal({ order, execution, loading, error, onRetry, onClose }: { order: Order | null; execution?: ExecutionView; loading: boolean; error: Error | null; onRetry: () => void; onClose: () => void }) {
  const progress = Math.max(0, Math.min(100, Number(execution?.progress || 0)));
  return (
    <Modal opened={Boolean(order)} onClose={onClose} title={null} fullScreen padding={0}>
      <Stack gap={0} h="100vh">
        <Paper radius={0} p="lg" withBorder>
          <Group justify="space-between" align="flex-start">
            <div>
              <Text size="xs" fw={800} c="blue">SELF-SERVICE EXECUTION</Text>
              <Title order={2}>{order?.productLabel || 'Live run'}</Title>
              <Group gap="xs" mt={6}>
                <Badge color={statusColor(execution?.status || order?.status || '')}>{(execution?.status || order?.status || '').replaceAll('_', ' ')}</Badge>
                {execution?.runType || order?.runType ? <Badge variant="outline">{typeLabel(execution?.runType || order?.runType || '')}</Badge> : null}
                {execution?.runRef || order?.runRef ? <Code>{execution?.runRef || order?.runRef}</Code> : null}
              </Group>
            </div>
            <Group>
              {execution?.modulePath ? <Button component="a" href={execution.modulePath} variant="light" leftSection={<IconExternalLink size={15} />}>Open module</Button> : null}
              <Button variant="default" onClick={onClose}>Close</Button>
            </Group>
          </Group>
        </Paper>

        <ScrollArea style={{ flex: 1 }}>
          <Stack p="xl" gap="lg" maw={1500} mx="auto">
            {loading && !execution ? <Group justify="center" p="xl"><Loader /><Text c="dimmed">Loading execution status...</Text></Group> : null}
            {error ? <Alert color="red" title="Execution status could not be loaded"><Group justify="space-between"><Text size="sm">{error.message}</Text><Button size="xs" variant="light" onClick={onRetry}>Retry</Button></Group></Alert> : null}
            {execution ? <>
              <Paper className="forge-card" p="lg">
                <Group justify="space-between" align="flex-end" mb="sm">
                  <div><Text size="xs" fw={800} c="dimmed">CURRENT STAGE</Text><Text size="xl" fw={850}>{execution.stage || 'Submitted'}</Text><Text size="sm" c="dimmed">{execution.message}</Text></div>
                  <Text fz={38} fw={900}>{Math.round(progress)}%</Text>
                </Group>
                <Progress value={progress} size="lg" radius="xl" animated={executionLive(execution.status)} />
              </Paper>

              <SimpleGrid cols={{ base: 2, md: 4 }}>
                <ExecutionMetric label="Current table" value={execution.currentTable || 'Waiting'} />
                <ExecutionMetric label="Rows read" value={formatNumber(execution.rowsRead)} />
                <ExecutionMetric label="Rows written" value={formatNumber(execution.rowsWritten)} />
                <ExecutionMetric label="Rejected" value={formatNumber(execution.rowsRejected)} tone={execution.rowsRejected > 0 ? 'red' : undefined} />
              </SimpleGrid>

              <Paper className="forge-card" p="lg">
                <Group justify="space-between" mb="md">
                  <div><Text fw={850}>Execution evidence</Text><Text size="sm" c="dimmed">Live downstream events retained with this self-service request.</Text></div>
                  <Text size="xs" c="dimmed">{execution.startedAt ? `Started ${formatWhen(execution.startedAt)}` : 'Awaiting engine start'}</Text>
                </Group>
                {execution.logs?.length ? <Timeline active={execution.logs.length} bulletSize={22} lineWidth={2}>{execution.logs.map((entry, index) => <Timeline.Item key={`${entry.at || 'log'}-${index}`} color={logColor(entry.level)} title={entry.label || entry.level} bullet={<IconActivity size={12} />}><Text size="sm">{entry.message}</Text><Text size="xs" c="dimmed">{formatWhen(entry.at)}</Text></Timeline.Item>)}</Timeline> : <Alert color="blue">The request is submitted. Engine events will appear here as the run starts.</Alert>}
              </Paper>
            </> : null}
          </Stack>
        </ScrollArea>
      </Stack>
    </Modal>
  );
}

function ExecutionMetric({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return <Paper className="forge-card" p="md"><Text size="xs" fw={800} c="dimmed">{label.toUpperCase()}</Text><Text size="xl" fw={850} c={tone}>{value}</Text></Paper>;
}

function RunnerModal({ runner, onClose }: { runner: Runner | null; onClose: () => void }) {
  return <Modal opened={Boolean(runner)} onClose={onClose} title="Automation runner" size="xl"><Stack><Alert color="blue">{runner?.note}</Alert><Command label="Launch approved request" value={runner?.launchCommand || ''} /><Command label="Read request status" value={runner?.statusCommand || ''} /></Stack></Modal>;
}
function Command({ label, value }: { label: string; value: string }) { return <div><Group justify="space-between" mb={5}><Text size="sm" fw={750}>{label}</Text><CopyButton value={value}>{({ copied, copy }) => <Button size="compact-xs" variant="subtle" onClick={copy}>{copied ? 'Copied' : 'Copy'}</Button>}</CopyButton></Group><Code block>{value}</Code></div>; }

type PublishDraft = ReturnType<typeof emptyPublish>;
function PublishModal({ opened, onClose, candidates, draft, setDraft, busy, onSubmit }: { opened: boolean; onClose: () => void; candidates: Candidate[]; draft: PublishDraft; setDraft: (value: PublishDraft) => void; busy: boolean; onSubmit: () => void }) {
  const candidate = candidates.find((item) => candidateKey(item) === draft.candidateKey);
  return <Modal opened={opened} onClose={onClose} title="Publish governed data product" size="xl"><Stack><Alert color="blue">Publication exposes a safe questionnaire, not the underlying credentials, masking policy, generator design, or mapping internals.</Alert><Select label="Approved artifact" searchable data={candidates.map((item) => ({ value: candidateKey(item), label: `${typeLabel(item.productType)} · ${item.name}` }))} value={draft.candidateKey} onChange={(value) => setDraft({ ...draft, candidateKey: value || '', label: candidates.find((item) => candidateKey(item) === value)?.name || draft.label, description: candidates.find((item) => candidateKey(item) === value)?.description || draft.description })} /><SimpleGrid cols={{ base: 1, sm: 2 }}><TextInput label="Catalog name" value={draft.label} onChange={(event) => setDraft({ ...draft, label: event.currentTarget.value })} /><TextInput label="Category" value={draft.category} onChange={(event) => setDraft({ ...draft, category: event.currentTarget.value })} placeholder="Payments, Customer 360, Core banking" /></SimpleGrid><Textarea label="Tester-facing description" value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.currentTarget.value })} minRows={3} /><TextInput label="Search tags" value={draft.tags} onChange={(event) => setDraft({ ...draft, tags: event.currentTarget.value })} placeholder="regression, cards, negative, masked" /><SimpleGrid cols={{ base: 1, sm: 2 }}><Select label="Approval" data={[{ value: 'REQUIRED', label: 'Always require' }, { value: 'OPTIONAL', label: 'Product policy' }, { value: 'NONE', label: 'Pre-approved instant' }]} value={draft.approvalMode} onChange={(value) => setDraft({ ...draft, approvalMode: value || 'REQUIRED' })} />{candidate?.productType === 'DATASCOPE' ? <NumberInput label="Maximum driver rows" description="Optional tester-controlled subset cap." min={1} value={draft.maxVolume} onChange={(value) => setDraft({ ...draft, maxVolume: typeof value === 'number' ? value : '' })} placeholder="Template maximum" /> : <Alert color="gray">Volume and delivery behavior remain locked to this artifact's approved design.</Alert>}</SimpleGrid><MultiSelect label="Allowed environments" data={DEFAULT_ENVIRONMENTS} value={draft.environments} onChange={(value) => setDraft({ ...draft, environments: value })} /><Textarea label="Delivery and usage instructions" value={draft.instructions} onChange={(event) => setDraft({ ...draft, instructions: event.currentTarget.value })} minRows={3} placeholder="Where data appears, cleanup responsibility, expected duration, and contact." />{candidate ? <Text size="xs" c="dimmed">The published questionnaire will be tailored for {typeLabel(candidate.productType)}.</Text> : null}<Group justify="flex-end"><Button variant="default" onClick={onClose}>Cancel</Button><Button loading={busy} disabled={!candidate || !draft.label.trim()} onClick={onSubmit}>Publish product</Button></Group></Stack></Modal>;
}

function CatalogManagement({ products, onToggle, onPublish }: { products: Product[]; onToggle: (product: Product, enabled: boolean) => void; onPublish: () => void }) {
  return <Stack><Group justify="space-between"><div><Text fw={850}>Published products</Text><Text size="sm" c="dimmed">Disable access immediately without changing the underlying approved artifact.</Text></div><Button leftSection={<IconAdjustments size={15} />} onClick={onPublish}>Publish</Button></Group>{products.map((product) => <Paper key={product.id} className="forge-card" p="sm"><Group justify="space-between"><div><Group gap="xs"><Text fw={750}>{product.label}</Text><Badge variant="light">{typeLabel(product.productType)}</Badge></Group><Text size="xs" c="dimmed">{product.category || 'General'} · artifact {product.artifactId} · owner {product.ownerUsername}</Text></div><Switch label={product.enabled ? 'Available' : 'Disabled'} checked={Boolean(product.enabled)} onChange={(event) => void onToggle(product, event.currentTarget.checked)} /></Group></Paper>)}</Stack>;
}

function capabilitiesFor(product: Product): Required<ProductCapabilities> {
  const server = product.capabilities || {};
  const virtual = product.productType.startsWith('VDB_');
  const fallbackDelivery = virtual ? ['VIRTUAL_DATABASE'] : product.productType === 'RESERVATION' ? ['RESERVATION'] : ['DATABASE'];
  return {
    supportsVolume: Boolean(server.supportsVolume ?? product.productType === 'DATASCOPE'),
    supportsVariety: Boolean(server.supportsVariety),
    supportsReservation: Boolean(server.supportsReservation),
    supportsLaunchWindow: Boolean(server.supportsLaunchWindow ?? true),
    supportsPointInTime: Boolean(server.supportsPointInTime ?? product.productType === 'VDB_PROVISION'),
    supportsRewind: Boolean(server.supportsRewind ?? product.productType === 'VDB_ROLLBACK'),
    supportsRefresh: Boolean(server.supportsRefresh ?? product.productType === 'VDB_REFRESH'),
    deliveryModes: server.deliveryModes?.length ? server.deliveryModes : fallbackDelivery,
    systems: server.systems || [],
    lockedControls: server.lockedControls?.length ? server.lockedControls : ['Source access', 'Data relationships', 'Masking and generation rules', 'Physical target'],
    outcome: server.outcome || 'Governed test data delivery'
  };
}

function fieldsFor(product: Product): QuestionField[] { return product.questionnaire?.fields?.length ? product.questionnaire.fields : questionnaireFor(product.productType); }
function questionnaireFor(type: string): QuestionField[] {
  if (type === 'RESERVATION') return [{ key: 'dataSourceId', label: 'Data source ID', type: 'NUMBER', required: true }, { key: 'table', label: 'Table', required: true }, { key: 'criteria', label: 'Business selection criteria', placeholder: "status = 'ACTIVE'" }, { key: 'count', label: 'Records to reserve', type: 'NUMBER', required: true }, { key: 'ttlHours', label: 'Reservation hours', type: 'NUMBER', required: true }];
  if (type === 'VDB_PROVISION') return [{ key: 'name', label: 'Virtual database name', required: true }, { key: 'targetDataSourceId', label: 'Optional target connection ID', type: 'NUMBER' }, { key: 'pointInTime', label: 'Optional point in time' }, { key: 'environmentId', label: 'Environment ID', type: 'NUMBER' }];
  if (type === 'VDB_REFRESH' || type === 'VDB_ROLLBACK') return [{ key: 'snapshotId', label: 'Approved snapshot ID', type: 'NUMBER', required: true }];
  if (type === 'SYNTHETIC') return [];
  if (type === 'MAPPING') return [{ key: 'seed', label: 'Deterministic masking seed', placeholder: 'Request-specific default' }];
  return [{ key: 'seed', label: 'Deterministic masking seed', placeholder: 'Leave blank for the published default' }];
}
function emptyRequest() { return { purpose: '', testType: 'FUNCTIONAL', environment: 'QA', parameters: {} as Record<string, unknown>, volume: '' as number | '', variety: '', deliveryMode: 'DATABASE', reserve: false, reservationHours: 24, scheduleAt: '' }; }
function emptyPublish() { return { candidateKey: '', label: '', description: '', category: 'Test data', tags: '', approvalMode: 'REQUIRED', maxVolume: '' as number | '', environments: ['QA', 'UAT'], instructions: '' }; }
function candidateKey(candidate: Candidate) { return `${candidate.productType}:${candidate.artifactId}`; }
function productIcon(type: string) { if (type === 'SYNTHETIC') return <IconSparkles size={19} />; if (type.startsWith('VDB')) return <IconDownload size={19} />; if (type === 'MAPPING') return <IconCode size={19} />; return <IconDatabase size={19} />; }
function typeLabel(type: string) { return type.replaceAll('_', ' ').toLowerCase().replace(/(^|\s)\S/g, (letter) => letter.toUpperCase()); }
function typeColor(type: string) { if (type === 'SYNTHETIC') return 'violet'; if (type.startsWith('VDB')) return 'teal'; if (type === 'RESERVATION') return 'orange'; if (type === 'MAPPING') return 'indigo'; return 'blue'; }
function statusColor(status: string) {
  const normalized = status.toUpperCase();
  if (['APPROVED', 'FULFILLED', 'COMPLETED', 'ACTIVE'].includes(normalized)) return 'green';
  if (['REJECTED', 'FAILED'].includes(normalized)) return 'red';
  if (normalized === 'CANCELED') return 'gray';
  if (['SUBMITTED', 'RUNNING', 'QUEUED'].includes(normalized)) return 'blue';
  return 'yellow';
}
function executionLive(status?: string | null) { return ['SUBMITTED', 'RUNNING', 'QUEUED'].includes(String(status || '').toUpperCase()); }
function logColor(level: string) { if (level === 'ERROR') return 'red'; if (level === 'WARN') return 'yellow'; if (level === 'SUCCESS') return 'green'; return 'blue'; }
function formatNumber(value?: number | null) { return Number(value || 0).toLocaleString(); }
function formatWhen(value?: string | null) { if (!value) return ''; try { return new Date(value).toLocaleString(); } catch { return value; } }
function duration(seconds: number) { if (seconds < 60) return `${seconds}s`; if (seconds < 3600) return `${Math.round(seconds / 60)}m`; return `${(seconds / 3600).toFixed(1)}h`; }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
