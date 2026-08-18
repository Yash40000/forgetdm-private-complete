'use client';

import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Badge,
  Button,
  Divider,
  Group,
  Loader,
  Modal,
  Paper,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconBaselineDensityMedium,
  IconCheck,
  IconClock,
  IconHistory,
  IconListSearch,
  IconRefresh,
  IconShieldCheck
} from '@tabler/icons-react';

import { StatusPill } from '@/components/status-pill';
import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DriftHistoryEntry, DriftIssue, DriftReport, PiiCoverage } from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import { piiCoverageCount } from '../utils';
import { MiniStat } from './bits';

type AcceptanceMode = 'capture' | 'accept' | null;

export function GuardrailsPanel({
  datasetId,
  coverage,
  drift,
  loading
}: {
  datasetId: number;
  coverage?: PiiCoverage;
  drift?: DriftReport;
  loading: boolean;
}) {
  const [gapsOpen, setGapsOpen] = useState(false);
  const [gapSearch, setGapSearch] = useState('');

  if (loading) {
    return (
      <Group>
        <Loader size="sm" />
        <Text c="dimmed">Loading retained guardrail evidence...</Text>
      </Group>
    );
  }

  const gapRows: Array<{ table?: string; column?: string; piiType?: string }> =
    Array.isArray(coverage?.unmaskedApproved) && coverage.unmaskedApproved.length
      ? coverage.unmaskedApproved
      : (coverage?.gaps || []).map((gap) => ({ table: gap.tableName, column: gap.columnName, piiType: gap.piiType }));
  const piiGapCount = piiCoverageCount(coverage, 'unmasked');
  const approvedPiiCount = piiCoverageCount(coverage, 'approved');
  const maskedPiiCount = piiCoverageCount(coverage, 'masked');
  const gapQuery = gapSearch.trim().toLowerCase();
  const filteredGaps = gapQuery
    ? gapRows.filter((gap) => [gap.table, gap.column, gap.piiType].some((value) => String(value || '').toLowerCase().includes(gapQuery)))
    : gapRows;
  const gapTotal = Math.max(piiGapCount, gapRows.length);

  return (
    <Stack gap="lg">
      <Paper className="forge-card" p="md">
        <Group justify="space-between" mb="sm">
          <div>
            <Title order={3} size="h4">PII coverage</Title>
            <Text size="sm" c="dimmed">Approved sensitive columns must resolve to a policy or explicit column action.</Text>
          </div>
          <Group gap="xs">
            {gapTotal ? (
              <Button size="compact-sm" variant="light" color="yellow" leftSection={<IconListSearch size={14} />} onClick={() => setGapsOpen(true)}>
                Review {gapTotal} gap{gapTotal === 1 ? '' : 's'}
              </Button>
            ) : null}
            <StatusPill value={gapTotal ? 'WARN' : 'READY'} />
          </Group>
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <MiniStat label="Approved" value={approvedPiiCount} />
          <MiniStat label="Protected" value={maskedPiiCount} />
          <MiniStat label="Unprotected" value={gapTotal} />
        </SimpleGrid>
      </Paper>

      {gapTotal ? (
        <Alert color="yellow" icon={<IconAlertTriangle size={16} />} title="Approved PII can be copied without masking">
          Assign a policy or column override before provisioning. The backend provisioning gate remains independent of this visual warning.
        </Alert>
      ) : null}

      <SchemaDriftWorkspace datasetId={datasetId} drift={drift} />

      <Modal
        opened={gapsOpen}
        onClose={() => setGapsOpen(false)}
        title={`Unprotected approved PII - ${gapTotal} column${gapTotal === 1 ? '' : 's'}`}
        size="lg"
        scrollAreaComponent={ScrollArea.Autosize}
      >
        <Stack gap="sm">
          <TextInput
            placeholder="Filter by table, column, or PII type"
            value={gapSearch}
            onChange={(event) => setGapSearch(event.currentTarget.value)}
            autoCorrect="off"
            spellCheck={false}
            data-autofocus
          />
          <div className="forge-grid-panel">
            <Table stickyHeader highlightOnHover verticalSpacing="xs" horizontalSpacing="md">
              <Table.Thead>
                <Table.Tr><Table.Th>Table</Table.Th><Table.Th>Column</Table.Th><Table.Th>PII type</Table.Th></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {filteredGaps.map((gap, index) => (
                  <Table.Tr key={`${gap.table}-${gap.column}-${index}`}>
                    <Table.Td>{gap.table}</Table.Td>
                    <Table.Td>{gap.column}</Table.Td>
                    <Table.Td><Badge variant="light" color="yellow">{gap.piiType || 'PII'}</Badge></Table.Td>
                  </Table.Tr>
                ))}
                {!filteredGaps.length ? <Table.Tr><Table.Td colSpan={3}><Text size="sm" c="dimmed">No gaps match this filter.</Text></Table.Td></Table.Tr> : null}
              </Table.Tbody>
            </Table>
          </div>
        </Stack>
      </Modal>
    </Stack>
  );
}

export function SchemaDriftWorkspace({
  datasetId,
  drift,
  endpointBase,
  heading = 'Schema drift',
  description = 'Versioned source and target structure evidence. Provisioning performs a fresh blocking check.'
}: {
  datasetId: number;
  drift?: DriftReport;
  endpointBase?: string;
  heading?: string;
  description?: string;
}) {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const canAccept = can('provision.approve');
  const [busy, setBusy] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [severity, setSeverity] = useState<string | null>('ALL');
  const [scope, setScope] = useState<string | null>('ALL');
  const [acceptanceMode, setAcceptanceMode] = useState<AcceptanceMode>(null);
  const [reason, setReason] = useState('');
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [scheduleCron, setScheduleCron] = useState('0 0 2 * * *');
  const [scheduleZone, setScheduleZone] = useState('UTC');

  const basePath = endpointBase || `/api/datasets/${datasetId}/drift`;
  const driftKey = endpointBase ? keys.schemaDrift.monitor(datasetId) : keys.datascope.drift(datasetId);
  const historyKey = endpointBase ? keys.schemaDrift.history(datasetId) : [...keys.datascope.drift(datasetId), 'history'];

  const historyQuery = useQuery({
    queryKey: historyKey,
    queryFn: () => apiFetch<DriftHistoryEntry[]>(`${basePath}/history?limit=25`)
  });

  useEffect(() => {
    setScheduleEnabled(Boolean(drift?.schedule?.enabled));
    setScheduleCron(drift?.schedule?.cron || '0 0 2 * * *');
    setScheduleZone(drift?.schedule?.zone || 'UTC');
  }, [drift?.schedule?.enabled, drift?.schedule?.cron, drift?.schedule?.zone]);

  const issues = drift?.issues || [];
  const filteredIssues = useMemo(() => {
    const query = search.trim().toLowerCase();
    return issues.filter((issue) => {
      if (severity !== 'ALL' && issue.severity !== severity) return false;
      if (scope !== 'ALL' && issue.scope !== scope) return false;
      if (!query) return true;
      return [issue.type, issue.scope, issue.schema, issue.table, issue.column, issue.artifact, issue.detail]
        .some((value) => String(value || '').toLowerCase().includes(query));
    });
  }, [issues, scope, search, severity]);

  const refreshDrift = async (updated?: DriftReport) => {
    if (updated) queryClient.setQueryData(driftKey, updated);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: driftKey }),
      queryClient.invalidateQueries({ queryKey: historyKey }),
      ...(endpointBase ? [queryClient.invalidateQueries({ queryKey: keys.schemaDrift.monitors })] : [])
    ]);
  };

  const scanNow = async () => {
    setBusy('scan');
    try {
      const updated = await apiPost<DriftReport>(`${basePath}/check`, {});
      await refreshDrift(updated);
      notifications.show({ color: updated.blocking ? 'red' : updated.issues?.length ? 'yellow' : 'green', title: 'Schema drift scan complete', message: driftMessage(updated) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Schema drift scan failed', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const submitAcceptance = async () => {
    const cleanReason = reason.trim();
    if (cleanReason.length < 8 || !acceptanceMode) return;
    setBusy(acceptanceMode);
    try {
      const updated = acceptanceMode === 'capture'
        ? await apiPost<DriftReport>(`${basePath}/baseline`, { reason: cleanReason })
        : await apiPost<DriftReport>(`${basePath}/accept`, { runId: drift?.latestRunId, reason: cleanReason });
      await refreshDrift(updated);
      notifications.show({ color: 'green', title: acceptanceMode === 'capture' ? 'Baseline captured' : 'Scan accepted', message: `Active baseline v${updated.baseline?.version || 1}` });
      setAcceptanceMode(null);
      setReason('');
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not update baseline', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const saveSchedule = async () => {
    setBusy('schedule');
    try {
      const schedule = await apiPut<NonNullable<DriftReport['schedule']>>(`${basePath}/schedule`, {
        enabled: scheduleEnabled,
        cron: scheduleCron.trim() || null,
        zone: scheduleZone.trim() || 'UTC'
      });
      queryClient.setQueryData<DriftReport>(driftKey, (current) => ({ ...(current || {}), schedule }));
      notifications.show({ color: 'green', title: 'Drift schedule saved', message: schedule.enabled ? `${schedule.cron} (${schedule.zone})` : 'Scheduled checks disabled' });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not save schedule', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const status = drift?.status || 'BASELINE_REQUIRED';
  const severityCounts = drift?.summary?.severityCounts || {};

  return (
    <Paper className="forge-card" p="md">
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Group gap="xs"><Title order={3} size="h4">{heading}</Title><StatusPill value={status} /></Group>
            <Text size="sm" c="dimmed">{description}</Text>
          </div>
          <Group gap="xs">
            <Button variant="light" leftSection={<IconRefresh size={16} />} loading={busy === 'scan'} disabled={!drift?.baseline || !canManage} onClick={() => void scanNow()}>
              Scan now
            </Button>
            {canAccept ? (
              <Button
                variant={drift?.baseline ? 'default' : 'filled'}
                leftSection={<IconBaselineDensityMedium size={16} />}
                onClick={() => { setReason(''); setAcceptanceMode('capture'); }}
              >
                {drift?.baseline ? 'Capture new baseline' : 'Capture baseline'}
              </Button>
            ) : null}
            {canAccept && drift?.latestRunId && issues.length ? (
              <Button color="green" variant="light" leftSection={<IconCheck size={16} />} onClick={() => { setReason(''); setAcceptanceMode('accept'); }}>
                Accept scan
              </Button>
            ) : null}
          </Group>
        </Group>

        {drift?.baselineRequired ? (
          <Alert color="yellow" icon={<IconAlertTriangle size={16} />} title="An accepted baseline is required">
            Capture the intended source and target structures before this DataScope can provision data.
          </Alert>
        ) : null}
        {drift?.blocking ? (
          <Alert color="red" icon={<IconAlertTriangle size={16} />} title={`${drift.blockingCount || 0} blocking change${drift.blockingCount === 1 ? '' : 's'}`}>
            Provisioning is blocked until the change is corrected or this exact retained scan is accepted with a reason.
          </Alert>
        ) : null}

        <SimpleGrid cols={{ base: 2, md: 5 }}>
          <MiniStat label="Baseline" value={drift?.baseline ? `v${drift.baseline.version}` : 'Required'} />
          <MiniStat label="Blocking" value={drift?.blockingCount || 0} />
          <MiniStat label="High" value={severityCounts.HIGH || 0} />
          <MiniStat label="Other" value={Math.max(0, (issues.length || 0) - (drift?.blockingCount || 0))} />
          <MiniStat label="Last checked" value={formatDate(drift?.checkedAt)} />
        </SimpleGrid>

        {drift?.baseline ? (
          <Group gap="lg">
            <Text size="xs" c="dimmed">Accepted by <b>{drift.baseline.acceptedBy}</b> on {formatDate(drift.baseline.acceptedAt)}</Text>
            <Text size="xs" c="dimmed">Fingerprint <code>{drift.baseline.fingerprint.slice(0, 12)}</code></Text>
            <Text size="xs" c="dimmed">Reason: {drift.baseline.reason}</Text>
          </Group>
        ) : null}

        <Divider />

        <Stack gap="xs">
          <div>
            <Text fw={800}>Change evidence</Text>
            <Text size="xs" c="dimmed">Columns, keys, foreign keys, checks, indexes, and source/target reachability.</Text>
          </div>
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <TextInput placeholder="Filter object, change, or impact" value={search} onChange={(event) => setSearch(event.currentTarget.value)} />
            <Select value={severity} onChange={setSeverity} data={['ALL', 'BLOCKER', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']} allowDeselect={false} />
            <Select value={scope} onChange={setScope} data={['ALL', 'SOURCE', 'TARGET']} allowDeselect={false} />
          </SimpleGrid>
        </Stack>

        <div className="forge-grid-panel">
          <Table stickyHeader highlightOnHover verticalSpacing="xs" horizontalSpacing="sm">
            <Table.Thead>
              <Table.Tr><Table.Th>Severity</Table.Th><Table.Th>Scope</Table.Th><Table.Th>Object</Table.Th><Table.Th>Change</Table.Th><Table.Th>Impact</Table.Th></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {filteredIssues.map((issue, index) => <DriftIssueRow key={`${issue.scope}-${issue.table}-${issue.column}-${issue.type}-${index}`} issue={issue} />)}
              {!filteredIssues.length ? (
                <Table.Tr><Table.Td colSpan={5}><Group justify="center" py="md"><IconShieldCheck size={18} /><Text size="sm" c="dimmed">{issues.length ? 'No changes match these filters.' : drift?.latestRunId ? 'No schema changes detected.' : 'Run a scan to compare live metadata with the baseline.'}</Text></Group></Table.Td></Table.Tr>
              ) : null}
            </Table.Tbody>
          </Table>
        </div>

        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          <Paper withBorder p="sm">
            <Group justify="space-between" mb="xs"><div><Text fw={800}>Scheduled checks</Text><Text size="xs" c="dimmed">HA-safe checks retain evidence without rescanning on page refresh.</Text></div><IconClock size={18} /></Group>
            <SimpleGrid cols={{ base: 1, sm: 2, xl: 4 }}>
              <Switch label="Enabled" checked={scheduleEnabled} disabled={!canManage} onChange={(event) => setScheduleEnabled(event.currentTarget.checked)} />
              <TextInput label="Cron" value={scheduleCron} disabled={!canManage || !scheduleEnabled} onChange={(event) => setScheduleCron(event.currentTarget.value)} />
              <TextInput label="Zone" value={scheduleZone} disabled={!canManage || !scheduleEnabled} onChange={(event) => setScheduleZone(event.currentTarget.value)} />
              {canManage ? <Button variant="light" loading={busy === 'schedule'} onClick={() => void saveSchedule()}>Save schedule</Button> : null}
            </SimpleGrid>
            {drift?.schedule?.nextRunAt ? <Text size="xs" c="dimmed" mt="xs">Next {formatDate(drift.schedule.nextRunAt)}; last {formatDate(drift.schedule.lastRunAt)}</Text> : null}
          </Paper>

          <Paper withBorder p="sm">
            <Group justify="space-between" mb="xs"><div><Text fw={800}>Scan history</Text><Text size="xs" c="dimmed">Immutable evidence for manual, scheduled, and pre-provision checks.</Text></div><IconHistory size={18} /></Group>
            <ScrollArea.Autosize mah={190}>
              <Table verticalSpacing={5} horizontalSpacing="xs">
                <Table.Thead><Table.Tr><Table.Th>Run</Table.Th><Table.Th>Trigger</Table.Th><Table.Th>Status</Table.Th><Table.Th>Issues</Table.Th><Table.Th>Checked</Table.Th></Table.Tr></Table.Thead>
                <Table.Tbody>
                  {(historyQuery.data || []).map((entry) => (
                    <Table.Tr key={entry.id}><Table.Td>#{entry.id}</Table.Td><Table.Td>{entry.triggerType}</Table.Td><Table.Td><StatusPill value={entry.status || 'UNKNOWN'} /></Table.Td><Table.Td>{entry.issueCount || 0} / {entry.blockingCount || 0} blocking</Table.Td><Table.Td>{formatDate(entry.checkedAt)}</Table.Td></Table.Tr>
                  ))}
                  {!historyQuery.data?.length ? <Table.Tr><Table.Td colSpan={5}><Text size="xs" c="dimmed">No retained scans yet.</Text></Table.Td></Table.Tr> : null}
                </Table.Tbody>
              </Table>
            </ScrollArea.Autosize>
          </Paper>
        </SimpleGrid>
      </Stack>

      <Modal
        opened={acceptanceMode !== null}
        onClose={() => { setAcceptanceMode(null); setReason(''); }}
        title={acceptanceMode === 'capture' ? 'Capture accepted schema baseline' : 'Accept retained drift scan'}
        size="md"
      >
        <Stack gap="sm">
          <Alert color={acceptanceMode === 'capture' && drift?.baseline ? 'yellow' : 'blue'}>
            {acceptanceMode === 'capture'
              ? 'This reads live source and target metadata and creates a new immutable baseline version.'
              : `This promotes retained scan #${drift?.latestRunId} exactly as captured; no second live scan is performed.`}
          </Alert>
          <TextInput label="Acceptance reason" description="Required for audit evidence; minimum 8 characters." value={reason} onChange={(event) => setReason(event.currentTarget.value)} maxLength={1000} data-autofocus />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => { setAcceptanceMode(null); setReason(''); }}>Cancel</Button>
            <Button color="green" loading={busy === acceptanceMode} disabled={reason.trim().length < 8} onClick={() => void submitAcceptance()}>
              {acceptanceMode === 'capture' ? 'Capture and accept' : 'Accept as new baseline'}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Paper>
  );
}

function DriftIssueRow({ issue }: { issue: DriftIssue }) {
  const color = issue.severity === 'BLOCKER' || issue.severity === 'CRITICAL'
    ? 'red' : issue.severity === 'HIGH' ? 'orange' : issue.severity === 'MEDIUM' ? 'yellow' : 'blue';
  const object = [issue.schema, issue.table, issue.column].filter(Boolean).join('.');
  return (
    <Table.Tr>
      <Table.Td><Badge variant="light" color={color}>{issue.severity || 'INFO'}</Badge></Table.Td>
      <Table.Td><Badge variant="outline" color={issue.scope === 'TARGET' ? 'green' : 'blue'}>{issue.scope || 'SOURCE'}</Badge></Table.Td>
      <Table.Td><Text fw={700} size="sm">{object || issue.artifact || '-'}</Text><Text size="xs" c="dimmed">{issue.artifact}</Text></Table.Td>
      <Table.Td><Text fw={700} size="sm">{String(issue.type || 'CHANGE').replaceAll('_', ' ')}</Text><Text size="xs" c="dimmed">{issue.detail}</Text>{issue.beforeValue || issue.afterValue ? <Text size="xs" c="dimmed">Before: {issue.beforeValue || '-'} | After: {issue.afterValue || '-'}</Text> : null}</Table.Td>
      <Table.Td>{issue.affectedJobs?.length ? <Stack gap={2}>{issue.affectedJobs.slice(0, 3).map((job) => <Badge key={job} variant="light" color="gray">{job}</Badge>)}</Stack> : <Text size="xs" c="dimmed">No saved job reference</Text>}</Table.Td>
    </Table.Tr>
  );
}

function driftMessage(report: DriftReport) {
  const issues = report.issues?.length || 0;
  if (report.blocking) return `${report.blockingCount || 0} blocking change${report.blockingCount === 1 ? '' : 's'}; ${issues} total.`;
  return issues ? `${issues} non-blocking change${issues === 1 ? '' : 's'} retained for review.` : 'Source and target match the accepted baseline.';
}

function formatDate(value?: string | null) {
  if (!value) return 'Not checked';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
