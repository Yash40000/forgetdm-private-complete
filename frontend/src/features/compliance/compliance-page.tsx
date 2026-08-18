'use client';

import { useMemo, useState } from 'react';
import {
  Accordion,
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Code,
  Group,
  Loader,
  Modal,
  NumberInput,
  Paper,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  Textarea,
  TextInput,
  ThemeIcon,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconCheck,
  IconCircleCheck,
  IconDownload,
  IconFileTextShield,
  IconGavel,
  IconPlayerPlay,
  IconSearch,
  IconShieldCheck,
  IconTrash,
  IconUserSearch,
  IconX
} from '@tabler/icons-react';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { usePermissions } from '@/lib/use-permissions';
import {
  useComplianceDataSources,
  useComplianceMutations,
  useCompliancePolicies,
  useExceptions,
  usePosture,
  useScan,
  useScans
} from './hooks';
import type { ComplianceScan, EvidencePack, PiiException, ScanType } from './types';

const SCAN_TYPES: Array<{ value: ScanType; label: string; help: string }> = [
  {
    value: 'FULL',
    label: 'Full assurance scan',
    help: 'Coverage, leak and cardinality together — the scan to run before an audit.'
  },
  {
    value: 'COVERAGE',
    label: 'Coverage — is every PII field masked by a rule?',
    help: 'Compares discovered PII against the policy. Catches the commonest leak: a field nobody wrote a rule for.'
  },
  {
    value: 'LEAK',
    label: 'Leak — does any real PII survive?',
    help: 'Reads whole columns: flags values that are still valid real-world identifiers, and (with a source) any value identical to production.'
  },
  {
    value: 'CARDINALITY',
    label: 'Cardinality — did masking collapse the data?',
    help: 'Detects columns masked to one or few values, which hides per-record and cross-tenant defects from every test.'
  }
];

function severityColor(severity: string) {
  return severity === 'FAIL' ? 'red' : severity === 'WARN' ? 'yellow' : 'gray';
}

function resultColor(result?: string | null) {
  return result === 'FAIL' ? 'red' : result === 'WARN' ? 'yellow' : result === 'PASS' ? 'green' : 'gray';
}

function statusColor(status: string, expired: boolean) {
  if (expired) return 'red';
  return status === 'APPROVED' ? 'green' : status === 'PENDING' ? 'yellow' : 'gray';
}

function when(value?: string | null) {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.valueOf()) ? value : d.toLocaleString();
}

export function CompliancePage() {
  const { can } = usePermissions();
  const canRun = can('compliance.run');
  const canApprove = can('compliance.approve');

  const postureQuery = usePosture();
  const scansQuery = useScans(50);
  const exceptionsQuery = useExceptions();
  const dataSourcesQuery = useComplianceDataSources();
  const policiesQuery = useCompliancePolicies();
  const m = useComplianceMutations();

  const dataSources = useMemo(() => dataSourcesQuery.data ?? [], [dataSourcesQuery.data]);
  const policies = useMemo(() => policiesQuery.data ?? [], [policiesQuery.data]);
  const scans = useMemo(() => scansQuery.data ?? [], [scansQuery.data]);
  const exceptions = useMemo(() => exceptionsQuery.data ?? [], [exceptionsQuery.data]);

  const [selectedScanId, setSelectedScanId] = useState<number | null>(null);
  const scanDetail = useScan(selectedScanId);

  // ---- scan form
  const [scanType, setScanType] = useState<ScanType>('FULL');
  const [targetId, setTargetId] = useState<string | null>(null);
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [policyId, setPolicyId] = useState<string | null>(null);
  const [schemaName, setSchemaName] = useState('');
  const [environment, setEnvironment] = useState('SIT');

  // ---- subject search
  const [subjectValue, setSubjectValue] = useState('');
  const [subjectType, setSubjectType] = useState<string | null>(null);

  // ---- exception form
  const [exceptionOpen, setExceptionOpen] = useState(false);
  const [exDataSourceId, setExDataSourceId] = useState<string | null>(null);
  const [exScope, setExScope] = useState('');
  const [exPiiType, setExPiiType] = useState('');
  const [exJustification, setExJustification] = useState('');
  const [exControls, setExControls] = useState('');
  const [exDays, setExDays] = useState<number | string>(30);

  // ---- evidence pack
  const [pack, setPack] = useState<EvidencePack | null>(null);

  const sourceOptions = dataSources.map((d) => ({ value: String(d.id), label: d.name }));
  const policyOptions = policies.map((p) => ({ value: String(p.id), label: p.name }));

  const runScan = () => {
    if (!canRun || !targetId) return;
    m.runScan.mutate(
      {
        scanType,
        targetId: Number(targetId),
        sourceId: sourceId ? Number(sourceId) : null,
        policyId: policyId ? Number(policyId) : null,
        schemaName: schemaName.trim() || null,
        environment: environment.trim() || null
      },
      {
        onSuccess: (scan) => {
          setSelectedScanId(scan.id);
          notifications.show({
            color: resultColor(scan.result),
            title: `Scan ${scan.result}`,
            message: scan.summary ?? 'Scan complete'
          });
        },
        onError: (e: unknown) =>
          notifications.show({ color: 'red', title: 'Scan failed', message: (e as Error).message })
      }
    );
  };

  const runSubjectSearch = () => {
    if (!canRun || !subjectValue.trim()) return;
    m.subjectSearch.mutate(
      {
        subjectValue: subjectValue.trim(),
        piiType: subjectType,
        targetId: targetId ? Number(targetId) : null
      },
      {
        onSuccess: (scan) => {
          setSelectedScanId(scan.id);
          setSubjectValue('');
          notifications.show({
            color: scan.result === 'FAIL' ? 'red' : 'green',
            title: scan.result === 'FAIL' ? 'Subject is reachable' : 'Subject not reachable',
            message: scan.summary ?? 'Search complete'
          });
        },
        onError: (e: unknown) =>
          notifications.show({ color: 'red', title: 'Search failed', message: (e as Error).message })
      }
    );
  };

  const submitException = () => {
    if (!canRun || !exDataSourceId) return;
    m.requestException.mutate(
      {
        dataSourceId: Number(exDataSourceId),
        environment: environment.trim() || null,
        scope: exScope.trim(),
        piiType: exPiiType.trim() || null,
        justification: exJustification.trim(),
        compensatingControls: exControls.trim() || null,
        days: typeof exDays === 'number' ? exDays : Number(exDays) || 30
      },
      {
        onSuccess: () => {
          setExceptionOpen(false);
          setExScope('');
          setExPiiType('');
          setExJustification('');
          setExControls('');
          notifications.show({
            color: 'blue',
            title: 'Exception requested',
            message: 'It stays inactive until a different approver signs it off.'
          });
        },
        onError: (e: unknown) =>
          notifications.show({ color: 'red', title: 'Could not request exception', message: (e as Error).message })
      }
    );
  };

  const buildPack = () => {
    if (!targetId) {
      notifications.show({ color: 'yellow', title: 'Pick an environment', message: 'Choose the target to report on.' });
      return;
    }
    m.buildEvidencePack.mutate(
      {
        targetId: Number(targetId),
        sourceId: sourceId ? Number(sourceId) : null,
        policyId: policyId ? Number(policyId) : null,
        schemaName: schemaName.trim() || null
      },
      {
        onSuccess: setPack,
        onError: (e: unknown) =>
          notifications.show({ color: 'red', title: 'Could not build pack', message: (e as Error).message })
      }
    );
  };

  const downloadPack = () => {
    if (!targetId) return;
    const params = new URLSearchParams({ targetId });
    if (sourceId) params.set('sourceId', sourceId);
    if (policyId) params.set('policyId', policyId);
    if (schemaName.trim()) params.set('schemaName', schemaName.trim());
    window.open(`/api/compliance/evidence-pack/download?${params.toString()}`, '_blank');
  };

  const posture = postureQuery.data;

  return (
    <Stack gap="lg">
      <div>
        <Group gap="sm" align="center">
          <ThemeIcon size="lg" variant="light" color="teal">
            <IconShieldCheck size={20} />
          </ThemeIcon>
          <Title order={2}>Compliance Assurance</Title>
        </Group>
        <Text c="dimmed" size="sm" mt={4}>
          Evidence, not assurance. Prove every PII field is covered by a rule, prove no real production value survives
          in a masked environment, and answer a data-subject erasure request with a defensible result.
        </Text>
      </div>

      <QueryErrorBanner
        errors={[postureQuery.error, dataSourcesQuery.error, policiesQuery.error]}
        onRetry={() => postureQuery.refetch()}
        title="Could not load compliance posture"
      />

      {/* ------------------------------------------------------------- posture */}
      {posture && (
        <SimpleGrid cols={{ base: 2, sm: 3, lg: 6 }} spacing="sm">
          <PostureCard label="Scans on record" value={posture.scanCount} />
          <PostureCard label="Failing" value={posture.failing} color={posture.failing > 0 ? 'red' : 'green'} />
          <PostureCard label="Warnings" value={posture.warning} color={posture.warning > 0 ? 'yellow' : 'green'} />
          <PostureCard
            label="Exceptions approved"
            value={posture.exceptionsApproved}
            hint={`${posture.exceptionsPending} pending`}
          />
          <PostureCard
            label="Exceptions expired"
            value={posture.exceptionsExpired}
            color={posture.exceptionsExpired > 0 ? 'red' : 'green'}
          />
          <PostureCard
            label="Audit ledger"
            value={posture.auditChain === false ? 'BROKEN' : 'VERIFIED'}
            color={posture.auditChain === false ? 'red' : 'green'}
          />
        </SimpleGrid>
      )}

      <Tabs defaultValue="scan">
        <Tabs.List>
          <Tabs.Tab value="scan" leftSection={<IconPlayerPlay size={14} />}>
            Run a scan
          </Tabs.Tab>
          <Tabs.Tab value="results" leftSection={<IconSearch size={14} />}>
            Results ({scans.length})
          </Tabs.Tab>
          <Tabs.Tab value="subject" leftSection={<IconUserSearch size={14} />}>
            Subject erasure
          </Tabs.Tab>
          <Tabs.Tab value="exceptions" leftSection={<IconGavel size={14} />}>
            Exceptions ({exceptions.length})
          </Tabs.Tab>
          <Tabs.Tab value="evidence" leftSection={<IconFileTextShield size={14} />}>
            Evidence pack
          </Tabs.Tab>
        </Tabs.List>

        {/* ------------------------------------------------------------ run */}
        <Tabs.Panel value="scan" pt="md">
          <Paper withBorder p="md" radius="md">
            <Stack gap="md">
              <Select
                label="What do you want to prove?"
                data={SCAN_TYPES.map((s) => ({ value: s.value, label: s.label }))}
                value={scanType}
                onChange={(v) => setScanType((v as ScanType) ?? 'FULL')}
                allowDeselect={false}
                description={SCAN_TYPES.find((s) => s.value === scanType)?.help}
              />
              <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
                <Select
                  label="Environment to check (target)"
                  placeholder="Pick the masked environment"
                  data={sourceOptions}
                  value={targetId}
                  onChange={setTargetId}
                  searchable
                  required
                />
                <Select
                  label="Production source (optional)"
                  placeholder="Enables exact source-value comparison"
                  description="Without it, the leak scan still proves absence by pattern."
                  data={sourceOptions}
                  value={sourceId}
                  onChange={setSourceId}
                  searchable
                  clearable
                />
                <Select
                  label="Masking policy"
                  placeholder="Policy whose coverage is being evidenced"
                  data={policyOptions}
                  value={policyId}
                  onChange={setPolicyId}
                  searchable
                  clearable
                />
                <TextInput
                  label="Schema (optional)"
                  placeholder="Leave blank to scan every schema"
                  value={schemaName}
                  onChange={(e) => setSchemaName(e.currentTarget.value)}
                />
                <TextInput
                  label="Environment label"
                  placeholder="SIT, UAT, DEV…"
                  value={environment}
                  onChange={(e) => setEnvironment(e.currentTarget.value)}
                />
              </SimpleGrid>
              <Group justify="space-between">
                <Text size="xs" c="dimmed">
                  Scans are read-only and row-capped, so they are safe to run against a live environment.
                </Text>
                <Button
                  leftSection={<IconPlayerPlay size={16} />}
                  onClick={runScan}
                  loading={m.runScan.isPending}
                  disabled={!canRun || !targetId}
                >
                  Run scan
                </Button>
              </Group>
              {!canRun && (
                <Alert color="gray" variant="light">
                  You have read-only compliance access. Running a scan needs <Code>compliance.run</Code>.
                </Alert>
              )}
            </Stack>
          </Paper>
        </Tabs.Panel>

        {/* -------------------------------------------------------- results */}
        <Tabs.Panel value="results" pt="md">
          <QueryErrorBanner
            errors={[scansQuery.error, scanDetail.error]}
            onRetry={() => scansQuery.refetch()}
            title="Could not load scans"
          />
          <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
            <Paper withBorder radius="md" p="sm">
              <Text fw={600} size="sm" mb="xs">
                Scan history
              </Text>
              <ScrollArea h={420}>
                <Stack gap="xs">
                  {scans.length === 0 && (
                    <Text c="dimmed" size="sm">
                      No scans yet. Run one from the first tab.
                    </Text>
                  )}
                  {scans.map((scan) => (
                    <Card
                      key={scan.id}
                      withBorder
                      padding="sm"
                      radius="sm"
                      onClick={() => setSelectedScanId(scan.id)}
                      style={{
                        cursor: 'pointer',
                        borderColor: selectedScanId === scan.id ? 'var(--mantine-color-teal-5)' : undefined
                      }}
                    >
                      <Group justify="space-between" wrap="nowrap">
                        <div style={{ minWidth: 0 }}>
                          <Group gap={6}>
                            <Badge size="sm" color={resultColor(scan.result)}>
                              {scan.result ?? scan.status}
                            </Badge>
                            <Badge size="sm" variant="light">
                              {scan.scanType}
                            </Badge>
                          </Group>
                          <Text size="sm" fw={500} mt={4} truncate>
                            {scan.name ?? `Scan #${scan.id}`}
                          </Text>
                          <Text size="xs" c="dimmed" truncate>
                            {scan.summary ?? '—'}
                          </Text>
                          <Text size="xs" c="dimmed">
                            {scan.targetName ?? '—'} · {when(scan.startedAt)}
                          </Text>
                        </div>
                        {canRun && (
                          <Tooltip label="Delete this scan record">
                            <ActionIcon
                              variant="subtle"
                              color="red"
                              onClick={(e) => {
                                e.stopPropagation();
                                m.deleteScan.mutate(scan.id);
                              }}
                            >
                              <IconTrash size={15} />
                            </ActionIcon>
                          </Tooltip>
                        )}
                      </Group>
                    </Card>
                  ))}
                </Stack>
              </ScrollArea>
            </Paper>

            <Paper withBorder radius="md" p="sm">
              <Text fw={600} size="sm" mb="xs">
                Findings
              </Text>
              {scanDetail.isFetching && <Loader size="sm" />}
              {!selectedScanId && (
                <Text c="dimmed" size="sm">
                  Select a scan to see its findings.
                </Text>
              )}
              {scanDetail.data && <FindingsList scan={scanDetail.data} />}
            </Paper>
          </SimpleGrid>
        </Tabs.Panel>

        {/* -------------------------------------------------------- subject */}
        <Tabs.Panel value="subject" pt="md">
          <Paper withBorder p="md" radius="md">
            <Stack gap="md">
              <Alert color="blue" variant="light" icon={<IconUserSearch size={16} />}>
                <Text size="sm">
                  Answers a &quot;right to be forgotten&quot; request. The search reports two different things: whether
                  the subject&apos;s <b>raw identifier</b> is present in any environment, and whether a{' '}
                  <b>reversible crosswalk</b> exists that could re-identify masked rows. If neither exists, masking is
                  one-way and there is nothing in non-production to erase — that is the defensible answer.
                </Text>
                <Text size="xs" mt={6} c="dimmed">
                  The value you enter is used to query only. It is never stored — the scan keeps a salted one-way hash
                  so the same request can be re-evidenced later.
                </Text>
              </Alert>
              <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
                <TextInput
                  label="Subject identifier"
                  placeholder="e.g. the production SSN, email or customer id"
                  value={subjectValue}
                  onChange={(e) => setSubjectValue(e.currentTarget.value)}
                  required
                />
                <Select
                  label="Identifier type (optional)"
                  placeholder="Narrows which columns are probed"
                  data={['SSN', 'EMAIL', 'PHONE', 'CREDIT_CARD', 'IBAN', 'BANK_ACCOUNT', 'PASSPORT', 'PERSON_ID', 'FULL_NAME']}
                  value={subjectType}
                  onChange={setSubjectType}
                  clearable
                />
                <Select
                  label="Limit to one environment (optional)"
                  placeholder="Default: every non-production environment"
                  data={sourceOptions}
                  value={targetId}
                  onChange={setTargetId}
                  searchable
                  clearable
                />
              </SimpleGrid>
              <Group justify="flex-end">
                <Button
                  leftSection={<IconUserSearch size={16} />}
                  onClick={runSubjectSearch}
                  loading={m.subjectSearch.isPending}
                  disabled={!canRun || !subjectValue.trim()}
                >
                  Search for subject
                </Button>
              </Group>
              {scanDetail.data?.scanType === 'SUBJECT' && <FindingsList scan={scanDetail.data} />}
            </Stack>
          </Paper>
        </Tabs.Panel>

        {/* ----------------------------------------------------- exceptions */}
        <Tabs.Panel value="exceptions" pt="md">
          <QueryErrorBanner
            errors={[exceptionsQuery.error]}
            onRetry={() => exceptionsQuery.refetch()}
            title="Could not load exceptions"
          />
          <Stack gap="md">
            <Group justify="space-between">
              <Text size="sm" c="dimmed" style={{ maxWidth: 720 }}>
                Approved, time-boxed permission for unmasked production data in a non-production environment. Every
                exception needs a justification and an approver who is not the requester, and it <b>expires</b> — an
                overdue exception is reported as a control failure rather than quietly becoming permanent.
              </Text>
              <Button
                variant="light"
                leftSection={<IconGavel size={16} />}
                onClick={() => setExceptionOpen(true)}
                disabled={!canRun}
              >
                Request exception
              </Button>
            </Group>
            <Paper withBorder radius="md">
              <Table.ScrollContainer minWidth={980}>
                <Table striped highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>#</Table.Th>
                      <Table.Th>Scope</Table.Th>
                      <Table.Th>Environment</Table.Th>
                      <Table.Th>Status</Table.Th>
                      <Table.Th>Requested by</Table.Th>
                      <Table.Th>Approved by</Table.Th>
                      <Table.Th>Expires</Table.Th>
                      <Table.Th>Actions</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {exceptions.length === 0 && (
                      <Table.Tr>
                        <Table.Td colSpan={8}>
                          <Text c="dimmed" size="sm" ta="center" py="md">
                            No exceptions registered — all data in these environments is masked under policy.
                          </Text>
                        </Table.Td>
                      </Table.Tr>
                    )}
                    {exceptions.map((e) => (
                      <ExceptionRow
                        key={e.id}
                        row={e}
                        canApprove={canApprove}
                        canRun={canRun}
                        onApprove={(note) => m.approveException.mutate({ id: e.id, note })}
                        onReject={(reason) => m.rejectException.mutate({ id: e.id, reason })}
                        onRevoke={(reason) => m.revokeException.mutate({ id: e.id, reason })}
                        onDelete={() => m.deleteException.mutate(e.id)}
                      />
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </Paper>
          </Stack>
        </Tabs.Panel>

        {/* ------------------------------------------------------- evidence */}
        <Tabs.Panel value="evidence" pt="md">
          <Stack gap="md">
            <Paper withBorder p="md" radius="md">
              <Stack gap="sm">
                <Text size="sm" c="dimmed">
                  Compiles the classification inventory, policy coverage, execution evidence from the tamper-evident
                  ledger, scan verdicts, the exception register and the audit-chain integrity check into one dated
                  document. It contains no personal data — witnesses appear only as salted one-way hashes.
                </Text>
                <Group>
                  <Button
                    leftSection={<IconFileTextShield size={16} />}
                    onClick={buildPack}
                    loading={m.buildEvidencePack.isPending}
                    disabled={!targetId}
                  >
                    Build evidence pack
                  </Button>
                  <Button
                    variant="light"
                    leftSection={<IconDownload size={16} />}
                    onClick={downloadPack}
                    disabled={!targetId}
                  >
                    Download as Markdown
                  </Button>
                  {!targetId && (
                    <Text size="xs" c="dimmed">
                      Pick an environment on the &quot;Run a scan&quot; tab first.
                    </Text>
                  )}
                </Group>
              </Stack>
            </Paper>

            {pack && (
              <>
                <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="sm">
                  <PostureCard label="PII fields" value={pack.piiFieldCount} />
                  <PostureCard
                    label="Coverage"
                    value={`${pack.coveragePercent}%`}
                    color={pack.uncoveredFieldCount > 0 ? 'yellow' : 'green'}
                  />
                  <PostureCard
                    label="Uncovered"
                    value={pack.uncoveredFieldCount}
                    color={pack.uncoveredFieldCount > 0 ? 'red' : 'green'}
                  />
                  <PostureCard
                    label="Ledger"
                    value={pack.auditChainValid === false ? 'BROKEN' : 'VERIFIED'}
                    color={pack.auditChainValid === false ? 'red' : 'green'}
                  />
                </SimpleGrid>
                <Paper withBorder radius="md" p="md">
                  <ScrollArea h={520}>
                    <Code block style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                      {pack.markdown}
                    </Code>
                  </ScrollArea>
                </Paper>
              </>
            )}
          </Stack>
        </Tabs.Panel>
      </Tabs>

      {/* ------------------------------------------------- exception modal */}
      <Modal
        opened={exceptionOpen}
        onClose={() => setExceptionOpen(false)}
        title="Request a PII exception"
        size="lg"
      >
        <Stack gap="sm">
          <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
            An auditor will read this justification. Say what data is needed, why masked or synthetic data cannot
            satisfy the case, and what controls limit the exposure.
          </Alert>
          <Select
            label="Environment (data source)"
            data={sourceOptions}
            value={exDataSourceId}
            onChange={setExDataSourceId}
            searchable
            required
          />
          <TextInput
            label="Scope"
            description="schema.table.column, schema.table, or a whole schema"
            placeholder="public.customer.ssn"
            value={exScope}
            onChange={(e) => setExScope(e.currentTarget.value)}
            required
          />
          <TextInput
            label="PII type (optional)"
            placeholder="SSN"
            value={exPiiType}
            onChange={(e) => setExPiiType(e.currentTarget.value)}
          />
          <Textarea
            label="Justification"
            description="At least 20 characters"
            minRows={3}
            autosize
            value={exJustification}
            onChange={(e) => setExJustification(e.currentTarget.value)}
            required
          />
          <Textarea
            label="Compensating controls"
            description="Restricted access, shortened retention, network isolation…"
            minRows={2}
            autosize
            value={exControls}
            onChange={(e) => setExControls(e.currentTarget.value)}
          />
          <NumberInput
            label="Valid for (days)"
            description="Maximum 180. Renewal is a deliberate re-approval."
            min={1}
            max={180}
            value={exDays}
            onChange={setExDays}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setExceptionOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={submitException}
              loading={m.requestException.isPending}
              disabled={!exDataSourceId || !exScope.trim() || exJustification.trim().length < 20}
            >
              Submit request
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

/* -------------------------------------------------------------------- pieces */

function PostureCard({
  label,
  value,
  color,
  hint
}: {
  label: string;
  value: number | string;
  color?: string;
  hint?: string;
}) {
  return (
    <Paper withBorder p="sm" radius="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <Text size="xl" fw={700} c={color}>
        {value}
      </Text>
      {hint && (
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      )}
    </Paper>
  );
}

function FindingsList({ scan }: { scan: ComplianceScan }) {
  const findings = scan.findings ?? [];
  const fails = findings.filter((f) => f.severity === 'FAIL');
  const warns = findings.filter((f) => f.severity === 'WARN');
  const infos = findings.filter((f) => f.severity === 'INFO');

  return (
    <Stack gap="sm">
      <Group gap="xs">
        <Badge color={resultColor(scan.result)}>{scan.result ?? scan.status}</Badge>
        <Badge variant="light">{scan.scanType}</Badge>
        <Text size="xs" c="dimmed">
          {scan.columnsScanned} column(s) · {scan.rowsScanned.toLocaleString()} row(s)
        </Text>
      </Group>
      {scan.error && (
        <Alert color="red" variant="light" icon={<IconX size={16} />}>
          {scan.error}
        </Alert>
      )}
      {findings.length === 0 && (
        <Text c="dimmed" size="sm">
          No findings recorded.
        </Text>
      )}
      <ScrollArea h={340}>
        <Accordion variant="separated" chevronPosition="left">
          {[...fails, ...warns, ...infos].map((f) => (
            <Accordion.Item key={f.id} value={String(f.id)}>
              <Accordion.Control
                icon={
                  <ThemeIcon size="sm" variant="light" color={severityColor(f.severity)}>
                    {f.severity === 'FAIL' ? (
                      <IconX size={12} />
                    ) : f.severity === 'WARN' ? (
                      <IconAlertTriangle size={12} />
                    ) : (
                      <IconCircleCheck size={12} />
                    )}
                  </ThemeIcon>
                }
              >
                <Group gap={6} wrap="nowrap">
                  <Badge size="xs" color={severityColor(f.severity)}>
                    {f.check}
                  </Badge>
                  <Text size="xs" truncate style={{ maxWidth: 320 }}>
                    {[f.schema, f.table, f.column].filter(Boolean).join('.') || 'environment-wide'}
                  </Text>
                </Group>
              </Accordion.Control>
              <Accordion.Panel>
                <Stack gap={6}>
                  <Text size="sm">{f.detail}</Text>
                  {f.remediation && (
                    <Text size="xs" c="dimmed">
                      <b>Fix:</b> {f.remediation}
                    </Text>
                  )}
                  <Group gap="md">
                    {f.piiType && (
                      <Text size="xs" c="dimmed">
                        Type: {f.piiType}
                      </Text>
                    )}
                    {f.affectedRows > 0 && (
                      <Text size="xs" c="dimmed">
                        Rows: {f.affectedRows.toLocaleString()}
                      </Text>
                    )}
                    {f.evidenceHash && (
                      <Tooltip label="Salted one-way hash of a witness value — never the value itself">
                        <Text size="xs" c="dimmed" ff="monospace">
                          witness {f.evidenceHash}
                        </Text>
                      </Tooltip>
                    )}
                  </Group>
                </Stack>
              </Accordion.Panel>
            </Accordion.Item>
          ))}
        </Accordion>
      </ScrollArea>
    </Stack>
  );
}

function ExceptionRow({
  row,
  canApprove,
  canRun,
  onApprove,
  onReject,
  onRevoke,
  onDelete
}: {
  row: PiiException;
  canApprove: boolean;
  canRun: boolean;
  onApprove: (note?: string) => void;
  onReject: (reason: string) => void;
  onRevoke: (reason?: string) => void;
  onDelete: () => void;
}) {
  const pending = row.status === 'PENDING';
  const approved = row.status === 'APPROVED' && !row.expired;

  return (
    <Table.Tr>
      <Table.Td>{row.id}</Table.Td>
      <Table.Td>
        <Text size="sm" ff="monospace">
          {row.scope}
        </Text>
        {row.piiType && (
          <Text size="xs" c="dimmed">
            {row.piiType}
          </Text>
        )}
      </Table.Td>
      <Table.Td>{row.environment}</Table.Td>
      <Table.Td>
        <Badge size="sm" color={statusColor(row.status, row.expired)}>
          {row.expired ? 'EXPIRED' : row.status}
        </Badge>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{row.requestedBy}</Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{row.approvedBy ?? '—'}</Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{when(row.expiresAt)}</Text>
        {row.daysRemaining != null && !row.expired && (
          <Text size="xs" c={row.daysRemaining < 7 ? 'orange' : 'dimmed'}>
            {row.daysRemaining} day(s) left
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        <Group gap={4} wrap="nowrap">
          {pending && canApprove && (
            <>
              <Tooltip label="Approve (you cannot approve your own request)">
                <ActionIcon variant="light" color="green" onClick={() => onApprove()}>
                  <IconCheck size={15} />
                </ActionIcon>
              </Tooltip>
              <Tooltip label="Reject">
                <ActionIcon
                  variant="light"
                  color="red"
                  onClick={() => {
                    const reason = window.prompt('Reason for rejection?');
                    if (reason) onReject(reason);
                  }}
                >
                  <IconX size={15} />
                </ActionIcon>
              </Tooltip>
            </>
          )}
          {approved && canApprove && (
            <Tooltip label="Revoke early">
              <ActionIcon
                variant="light"
                color="orange"
                onClick={() => onRevoke(window.prompt('Reason for revoking?') ?? undefined)}
              >
                <IconGavel size={15} />
              </ActionIcon>
            </Tooltip>
          )}
          {!approved && canRun && (
            <Tooltip label="Delete record">
              <ActionIcon variant="subtle" color="red" onClick={onDelete}>
                <IconTrash size={15} />
              </ActionIcon>
            </Tooltip>
          )}
        </Group>
      </Table.Td>
    </Table.Tr>
  );
}
