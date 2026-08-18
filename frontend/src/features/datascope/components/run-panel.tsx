'use client';

import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  NumberInput,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  TextInput
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAdjustments, IconAlertTriangle, IconDeviceFloppy, IconHistory, IconPlayerPlay, IconTestPipe } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { NameInput } from '@/components/name-input';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import type {
  DataSetDefinition,
  DataSource,
  DriftReport,
  MaskingPolicy,
  PiiCoverage,
  SavedDataScopeJob,
  SubsetPlan,
  TableProfile
} from '@/lib/types';
import { duplicateTargets, equalsIgnoreCase, isProfileIncluded, numberOrNull, sourceName, technicalInputProps } from '../utils';
import { SavedJobsPanel } from './saved-jobs-panel';
import { ProvisionJobMonitor } from './job-monitor';

export type ProvisionForm = {
  name: string;
  policyId: string;
  loadAction: string;
  targetPrep: string;
  maskingSeed: string;
  maxRows: string;
  batchSize: string;
  fetchRows: string;
  checkpointRows: string;
  inFlightBatches: string;
  nativeLoadMode: string;
  oracleLoadProfile: string;
  chunkRetryLimit: string;
  keyColumns: string;
  inPlaceChunkKey: string;
  exchange: boolean;
  exchangePartition: string;
  exchangeTable: string;
  exchangeValidate: boolean;
};

export type RunPanelSection = 'build' | 'history' | 'saved';

const emptyProvisionForm = (blueprint: DataSetDefinition): ProvisionForm => ({
  name: `${blueprint.name || 'datascope'}-provision`,
  policyId: blueprint.policyId ? String(blueprint.policyId) : '',
  loadAction: 'REPLACE',
  targetPrep: 'DELETE',
  maskingSeed: '',
  maxRows: '',
  batchSize: '',
  fetchRows: '2000',
  checkpointRows: '100000',
  inFlightBatches: '3',
  nativeLoadMode: 'AUTO',
  oracleLoadProfile: 'AUTO',
  chunkRetryLimit: '3',
  keyColumns: '',
  inPlaceChunkKey: '',
  exchange: false,
  exchangePartition: '',
  exchangeTable: '',
  exchangeValidate: false
});

/**
 * Run tab: dry-run plan preview, the full provision designer (launch through the
 * maker-checker job gate), save-as-job, and the saved-job lifecycle below.
 */
export function RunPanel({
  blueprint,
  profiles,
  policies,
  dataSources,
  drift,
  savedJobs,
  initialSection = 'build'
}: {
  blueprint: DataSetDefinition;
  profiles: TableProfile[];
  policies: MaskingPolicy[];
  dataSources: DataSource[];
  drift?: DriftReport;
  savedJobs: SavedDataScopeJob[];
  initialSection?: RunPanelSection;
}) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const canProvisionRun = can('provision.run');
  const canOverrideDrift = can('provision.approve');
  const canConfigure = canManage || canProvisionRun;
  const [form, setForm] = useState<ProvisionForm>(() => emptyProvisionForm(blueprint));
  const [previewRows, setPreviewRows] = useState('');
  const [plan, setPlan] = useState<SubsetPlan | null>(null);
  const [saveOpened, setSaveOpened] = useState(false);
  const [saveName, setSaveName] = useState('');
  const [saveDescription, setSaveDescription] = useState('');
  const [driftOverrideOpened, setDriftOverrideOpened] = useState(false);
  const [driftOverrideReason, setDriftOverrideReason] = useState('');
  const [busyAction, setBusyAction] = useState<'launch' | 'save' | null>(null);
  const [activeSection, setActiveSection] = useState<RunPanelSection>(initialSection);
  const selectedTarget = dataSources.find((source) => source.id === (blueprint.targetDataSourceId || blueprint.dataSourceId));
  const oracleTarget = String(selectedTarget?.kind || '').toUpperCase() === 'ORACLE';

  const previewPlan = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('DataScope management permission is required to preview a subset plan.');
      return apiPost<SubsetPlan>(`/api/datasets/${blueprint.id}/preview`, {
        maxDriverRows: numberOrNull(previewRows) || 0
      });
    },
    onSuccess: (result) => setPlan(result),
    onError: (error) => {
      setPlan(null);
      notifications.show({ color: 'red', title: 'Plan preview failed', message: error.message });
    }
  });

  /** Port of the classic console's payload builder, guardrails included. Returns null when aborted. */
  const buildPayload = async (approvedDriftOverride?: string, forLaunch = false) => {
    const inPlace = form.loadAction === 'IN_PLACE';
    const sourceOverrides = profiles.some((p) => p.sourceDataSourceId || p.sourceSchemaName);
    if (!inPlace && !blueprint.driverTable && !sourceOverrides) {
      notifications.show({ color: 'red', title: 'Driver required', message: 'Select and save a driver table first (Table profiles tab).' });
      return null;
    }
    if (inPlace && sourceOverrides) {
      notifications.show({
        color: 'red',
        title: 'In-place needs one source',
        message: 'In-place masking requires all mapped tables to use the default source DB and schema.'
      });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.jobs });
      return null;
    }
    if (!inPlace && !blueprint.targetDataSourceId) {
      notifications.show({ color: 'red', title: 'Target required', message: 'Set a target data source in the Table profiles defaults first.' });
      return null;
    }
    const included = profiles.filter(isProfileIncluded);
    if (!included.length) {
      notifications.show({ color: 'red', title: 'Nothing in scope', message: 'Include at least one table profile first.' });
      return null;
    }
    if (!inPlace) {
      const duplicates = duplicateTargets(included);
      if (duplicates.size) {
        notifications.show({ color: 'red', title: 'Duplicate target table', message: Array.from(duplicates).join(', ') });
        return null;
      }
      const selfLoad = included.find(
        (p) =>
          (p.sourceDataSourceId || blueprint.dataSourceId) === blueprint.targetDataSourceId &&
          equalsIgnoreCase(p.sourceSchemaName || blueprint.schemaName, blueprint.targetSchemaName || blueprint.schemaName) &&
          equalsIgnoreCase(p.targetTableName || p.tableName, p.tableName)
      );
      if (selfLoad) {
        notifications.show({
          color: 'red',
          title: 'Source and target are the same table',
          message: `${selfLoad.tableName}: choose a different target data source, schema, or target table.`
        });
        return null;
      }
    }
    const badPolicy = included.find((p) => p.policyId && !policies.some((policy) => policy.id === p.policyId));
    if (badPolicy) {
      notifications.show({
        color: 'red',
        title: 'Missing policy',
        message: `Saved policy for ${badPolicy.tableName} no longer exists. Pick another in the table map.`
      });
      return null;
    }

    // Guardrail 1: approved PII columns in scope with no masking.
    try {
      const policyId = numberOrNull(form.policyId);
      const coverage = await apiFetch<PiiCoverage>(
        `/api/datasets/${blueprint.id}/pii-coverage${policyId ? `?policyId=${policyId}` : ''}`
      );
      const unmasked = Array.isArray(coverage.unmaskedApproved) ? coverage.unmaskedApproved : [];
      if (unmasked.length) {
        const shown = unmasked
          .slice(0, 10)
          .map((u) => `• ${u.table}.${u.column}  (${u.piiType})`)
          .join('\n');
        const ok = await confirm({
          title: '⚠ Unmasked PII in scope',
          danger: true,
          okText: 'Provision anyway',
          message:
            `${unmasked.length} approved PII column${unmasked.length === 1 ? '' : 's'} in scope ` +
            `${unmasked.length === 1 ? 'has' : 'have'} NO masking rule or override:\n\n${shown}` +
            (unmasked.length > 10 ? `\n…and ${unmasked.length - 10} more` : '') +
            '\n\nThese values will be copied IN CLEAR to the target. Provision anyway?'
        });
        if (!ok) return null;
      }
    } catch {
      /* the coverage check is advisory — never block provisioning when it can't run */
    }

    // Guardrail 2: blueprint out of sync with the live source schema.
    const driftIssues = drift?.issues || [];
    const driftCount =
      driftIssues.length ||
      Number(drift?.missingTables?.length || 0) + Number(drift?.missingColumns?.length || 0) + Number(drift?.changedColumns?.length || 0);
    if (driftCount && forLaunch && !approvedDriftOverride?.trim()) {
      const shown = driftIssues
        .slice(0, 8)
        .map((i) => `• ${String(i.type || 'drift').replaceAll('_', ' ').toLowerCase()}: ${i.table || ''}${i.column ? '.' + i.column : ''}`)
        .join('\n');
      const ok = await confirm({
        title: '⚠ Schema drift detected',
        danger: true,
        okText: 'Provision anyway',
        message:
          `This blueprint no longer matches the live source schema (${driftCount} issue${driftCount === 1 ? '' : 's'}).` +
          (shown ? `\n\n${shown}` : '') +
          '\n\nThe job may fail or silently skip data. Provision anyway?'
      });
      if (!ok) return null;
    }

    const maxRows = numberOrNull(form.maxRows) || 0;
    const spec: Record<string, unknown> = {
      driverTable: blueprint.driverTable,
      filter: blueprint.driverFilter || null,
      maxDriverRows: maxRows > 0 ? maxRows : 0,
      sourceSchema: blueprint.schemaName || null,
      targetSchema: inPlace ? blueprint.schemaName || null : blueprint.targetSchemaName || null,
      maskingSeed: form.maskingSeed.trim() || null,
      loadAction: form.loadAction,
      targetPrep: form.targetPrep || (form.loadAction === 'REPLACE' ? 'DELETE' : 'NONE')
    };
    if (approvedDriftOverride?.trim()) spec.driftOverrideReason = approvedDriftOverride.trim();
    const keyColumns = form.keyColumns.split(',').map((s) => s.trim()).filter(Boolean);
    if (keyColumns.length) spec.keyColumns = keyColumns;
    const batchSize = numberOrNull(form.batchSize) || 0;
    if (batchSize > 0) spec.batchSize = batchSize;
    spec.fetchRows = numberOrNull(form.fetchRows) || 2000;
    spec.checkpointRows = numberOrNull(form.checkpointRows) || 100000;
    spec.inFlightBatches = numberOrNull(form.inFlightBatches) || 3;
    spec.nativeLoadMode = form.nativeLoadMode || 'AUTO';
    spec.oracleLoadProfile = form.oracleLoadProfile || 'AUTO';
    spec.chunkRetryLimit = numberOrNull(form.chunkRetryLimit) || 3;
    if (inPlace && form.inPlaceChunkKey.trim()) spec.inPlaceChunkKey = form.inPlaceChunkKey.trim();
    if (form.exchange) {
      if (!form.exchangePartition.trim()) {
        notifications.show({ color: 'red', title: 'Partition required', message: 'Enter the partition name for partition exchange.' });
        return null;
      }
      spec.exchangePartition = form.exchangePartition.trim();
      if (form.exchangeTable.trim()) spec.exchangeTable = form.exchangeTable.trim();
      if (form.exchangeValidate) spec.exchangeValidate = true;
    }

    // In-place + subset is destructive: it DELETES the non-subset rows in place.
    if (inPlace) {
      const subset =
        maxRows > 0 ||
        !!(blueprint.driverFilter && blueprint.driverFilter.trim()) ||
        included.some((p) => (p.rowLimit && p.rowLimit > 0) || (p.filterExpr && p.filterExpr.trim()));
      if (subset) {
        const ok = await confirm({
          title: '⚠ In-place + subset is destructive',
          danger: true,
          okText: 'Delete & mask',
          message:
            `This permanently DELETES the non-matching rows from the selected table(s) in "${sourceName(blueprint.dataSourceId, dataSources)}" ` +
            '(the database is modified in place — this cannot be undone), then masks what remains.\n\n' +
            'Run this only against a non-production copy. Continue?'
        });
        if (!ok) return null;
        spec.confirmInPlaceSubsetDelete = true;
      }
    }

    return {
      name: form.name.trim() || `${blueprint.name || 'datascope'}-provision`,
      jobType: 'SUBSET_MASK',
      sourceId: blueprint.dataSourceId,
      targetId: inPlace ? blueprint.dataSourceId : blueprint.targetDataSourceId,
      policyId: numberOrNull(form.policyId),
      datasetId: blueprint.id,
      specJson: JSON.stringify(spec)
    };
  };

  const launch = async (approvedDriftOverride?: string) => {
    if (!canProvisionRun || busyAction) return;
    setBusyAction('launch');
    try {
      const payload = await buildPayload(approvedDriftOverride, true);
      if (!payload) return;
      const job = await apiPost<{ id?: number; status?: string }>('/api/jobs', payload);
      notifications.show({
        color: job.status === 'AWAITING_APPROVAL' ? 'yellow' : 'green',
        title: job.status === 'AWAITING_APPROVAL' ? 'Submitted for approval' : 'Provision launched',
        message:
          job.status === 'AWAITING_APPROVAL'
            ? 'An approver must sign off before it runs. Track the request in the provision monitor below.'
            : `Job #${job.id ?? '?'} is running. Track it in the provision monitor below.`
      });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.jobs });
      setDriftOverrideOpened(false);
      setDriftOverrideReason('');
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not launch provision', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const requestLaunch = () => {
    const blockedByDrift = Boolean(drift?.baselineRequired || drift?.blocking);
    if (!blockedByDrift) {
      void launch();
      return;
    }
    if (!canOverrideDrift) {
      notifications.show({
        color: 'red',
        title: 'Schema review required',
        message: drift?.baselineRequired
          ? 'Capture an accepted schema baseline in Guardrails before provisioning.'
          : 'Blocking schema drift must be corrected or accepted in Guardrails by an authorized reviewer.'
      });
      return;
    }
    setDriftOverrideReason('');
    setDriftOverrideOpened(true);
  };

  const openSaveModal = () => {
    if (!canManage) return;
    setSaveName(form.name.trim() || `${blueprint.name || 'datascope'}-provision`);
    setSaveDescription('');
    setSaveOpened(true);
  };

  const saveAsJob = async () => {
    if (!canManage || busyAction) return;
    setBusyAction('save');
    try {
      const payload = await buildPayload();
      if (!payload) return;
      await apiPost('/api/datascope/saved-jobs', {
        name: saveName.trim(),
        description: saveDescription.trim(),
        spec: payload
      });
      notifications.show({ color: 'green', title: 'DataScope job saved', message: saveName.trim() });
      setSaveOpened(false);
      await queryClient.invalidateQueries({ queryKey: keys.datascope.savedJobs });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not save job', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  /** Prefill the designer from a saved job's spec (the "Load" action below). */
  const loadFromSpec = (spec: Record<string, unknown>) => {
    const inner = safeParse(spec.specJson) || {};
    setForm((current) => ({
      ...current,
      name: String(spec.name || current.name),
      policyId: spec.policyId ? String(spec.policyId) : '',
      loadAction: String(inner.loadAction || 'REPLACE'),
      targetPrep: String(inner.targetPrep || 'DELETE'),
      maskingSeed: inner.maskingSeed ? String(inner.maskingSeed) : '',
      maxRows: inner.maxDriverRows ? String(inner.maxDriverRows) : '',
      batchSize: inner.batchSize ? String(inner.batchSize) : '',
      fetchRows: inner.fetchRows ? String(inner.fetchRows) : '2000',
      checkpointRows: inner.checkpointRows ? String(inner.checkpointRows) : '100000',
      inFlightBatches: inner.inFlightBatches ? String(inner.inFlightBatches) : '3',
      nativeLoadMode: inner.nativeLoadMode ? String(inner.nativeLoadMode) : 'AUTO',
      oracleLoadProfile: inner.oracleLoadProfile ? String(inner.oracleLoadProfile) : 'AUTO',
      chunkRetryLimit: inner.chunkRetryLimit ? String(inner.chunkRetryLimit) : '3',
      keyColumns: Array.isArray(inner.keyColumns) ? (inner.keyColumns as string[]).join(', ') : '',
      inPlaceChunkKey: inner.inPlaceChunkKey ? String(inner.inPlaceChunkKey) : '',
      exchange: !!inner.exchangePartition,
      exchangePartition: inner.exchangePartition ? String(inner.exchangePartition) : '',
      exchangeTable: inner.exchangeTable ? String(inner.exchangeTable) : '',
      exchangeValidate: !!inner.exchangeValidate
    }));
    notifications.show({ color: 'blue', title: 'Job loaded into designer', message: 'Review the settings, then Launch or re-save.' });
  };

  const inPlace = form.loadAction === 'IN_PLACE';
  const planTables = plan ? plan.loadOrder && plan.loadOrder.length ? plan.loadOrder : Object.keys(plan.rowCounts || {}) : [];

  return (
    <Stack gap="md">
      {confirmElement}

      <Tabs value={activeSection} onChange={(value) => setActiveSection((value || 'build') as RunPanelSection)} keepMounted>
        <Tabs.List className="forge-tabs-list datascope-run-tabs">
          <Tabs.Tab value="build" leftSection={<IconAdjustments size={15} />}>Build &amp; launch</Tabs.Tab>
          <Tabs.Tab value="history" leftSection={<IconHistory size={15} />}>Run history</Tabs.Tab>
          <Tabs.Tab value="saved" leftSection={<IconDeviceFloppy size={15} />}>Saved jobs {savedJobs.length ? `(${savedJobs.length})` : ''}</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="build" pt="md">
          <Stack gap="md">

      <Paper className="forge-card" p="md">
        <Stack gap="sm">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={800}>Dry-run plan preview</Text>
              <Text size="sm" c="dimmed">
                Walks the FK closure and counts rows per table WITHOUT writing anything — see exactly what a run would move.
              </Text>
            </div>
            <Group align="flex-end" gap="xs">
              <NumberInput
                label="Cap driver rows"
                placeholder="optional"
                min={1}
                 w={140}
                 value={previewRows === '' ? '' : Number(previewRows)}
                 disabled={!canManage}
                onChange={(value) => setPreviewRows(value === '' || value === null ? '' : String(value))}
              />
              <Button leftSection={<IconTestPipe size={16} />} loading={previewPlan.isPending} disabled={!canManage} onClick={() => previewPlan.mutate()}>
                Preview plan
              </Button>
            </Group>
          </Group>
          {plan ? (
            <>
              <Group gap="xs">
                <Badge variant="light">{plan.mode || 'REFERENTIAL_CLOSURE'}</Badge>
                <Badge variant="light" color="blue">
                  {Number(plan.totalRows || 0).toLocaleString()} total row(s)
                </Badge>
                <Badge variant="light">{planTables.length} table(s)</Badge>
              </Group>
              {(plan.warnings || []).map((warning) => (
                <Alert key={warning} color="yellow" icon={<IconAlertTriangle size={16} />} py={6}>
                  {warning}
                </Alert>
              ))}
              <div className="forge-grid-panel">
                <table className="forge-table">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Table (load order, parents first)</th>
                      <th>Rows selected</th>
                    </tr>
                  </thead>
                  <tbody>
                    {planTables.map((table, idx) => (
                      <tr key={table}>
                        <td>{idx + 1}</td>
                        <td>
                          <Text fw={700} size="sm">
                            {table}
                          </Text>
                        </td>
                        <td>{Number(plan.rowCounts?.[table] ?? 0).toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : null}
        </Stack>
      </Paper>

      <Paper className="forge-card" p="md">
        <Stack gap="sm">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={800}>Provision run designer</Text>
              <Text size="sm" c="dimmed">
                {sourceName(blueprint.dataSourceId, dataSources)} → {inPlace ? 'same database (in-place)' : sourceName(blueprint.targetDataSourceId, dataSources)}.
                Launch goes through the maker-checker gate when governance requires approval.
              </Text>
            </div>
          </Group>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
            <NameInput label="Run name" value={form.name} disabled={!canConfigure} onChange={(value) => setForm({ ...form, name: value })} />
            <Select
              label="Default masking policy"
              data={[{ value: '', label: 'No default policy' }].concat(policies.map((p) => ({ value: String(p.id), label: p.name })))}
              value={form.policyId}
              searchable
              disabled={!canConfigure}
              onChange={(value) => setForm({ ...form, policyId: value || '' })}
            />
            <Select
              label="Load action"
              data={[
                { value: 'REPLACE', label: 'Replace target rows' },
                { value: 'INSERT', label: 'Insert' },
                { value: 'INSERT_UPDATE', label: 'Insert / update (merge)' },
                { value: 'UPDATE', label: 'Update only' },
                { value: 'TRUNCATE_ONLY', label: 'Truncate only' },
                { value: 'IN_PLACE', label: 'In-place mask (source itself!)' }
              ]}
              value={form.loadAction}
              disabled={!canConfigure}
              onChange={(value) => setForm({ ...form, loadAction: value || 'REPLACE' })}
            />
            <Select
              label="Target prep"
              data={[
                { value: 'DELETE', label: 'Delete rows first' },
                { value: 'TRUNCATE', label: 'Truncate first' },
                { value: 'TRUNCATE_CASCADE', label: 'Truncate cascade' },
                { value: 'NONE', label: 'None' }
              ]}
              value={form.targetPrep}
              disabled={!canConfigure}
              onChange={(value) => setForm({ ...form, targetPrep: value || 'NONE' })}
            />
            <TextInput
              {...technicalInputProps}
              label="Masking seed"
              placeholder="optional deterministic seed"
              value={form.maskingSeed}
              disabled={!canConfigure}
              onChange={(e) => setForm({ ...form, maskingSeed: e.currentTarget.value })}
            />
            <NumberInput
              label="Max driver rows"
              placeholder="optional cap"
              min={1}
              value={form.maxRows === '' ? '' : Number(form.maxRows)}
              disabled={!canConfigure}
              onChange={(value) => setForm({ ...form, maxRows: value === '' || value === null ? '' : String(value) })}
            />
            <NumberInput
              label="Batch size"
              placeholder="engine default"
              min={1}
              value={form.batchSize === '' ? '' : Number(form.batchSize)}
              disabled={!canConfigure}
              onChange={(value) => setForm({ ...form, batchSize: value === '' || value === null ? '' : String(value) })}
            />
            <TextInput
              {...technicalInputProps}
              label="Merge key columns"
              placeholder="for insert/update, e.g. id"
              value={form.keyColumns}
              disabled={!canConfigure}
              onChange={(e) => setForm({ ...form, keyColumns: e.currentTarget.value })}
            />
          </SimpleGrid>
          <details className="ds-scale-controls">
            <summary>
              <span>Enterprise scale controls</span>
              <Text component="span" size="xs" c="dimmed">Bounded streaming, checkpoints, backpressure, and native loading</Text>
            </summary>
            <SimpleGrid cols={{ base: 1, sm: 2, lg: oracleTarget ? 3 : 5 }} mt="sm">
              <NumberInput
                label="Source fetch rows"
                description="Rows requested per server cursor fetch."
                min={100}
                max={50000}
                step={500}
                value={form.fetchRows === '' ? '' : Number(form.fetchRows)}
                disabled={!canConfigure}
                onChange={(value) => setForm({ ...form, fetchRows: value === '' || value === null ? '' : String(value) })}
              />
              <NumberInput
                label="Checkpoint rows"
                description="Maximum rows committed per restartable chunk."
                min={1}
                max={1000000}
                step={10000}
                value={form.checkpointRows === '' ? '' : Number(form.checkpointRows)}
                disabled={!canConfigure}
                onChange={(value) => setForm({ ...form, checkpointRows: value === '' || value === null ? '' : String(value) })}
              />
              <NumberInput
                label="Buffered chunks"
                description="Bounded producer/consumer queue depth."
                min={1}
                max={16}
                value={form.inFlightBatches === '' ? '' : Number(form.inFlightBatches)}
                disabled={!canConfigure}
                onChange={(value) => setForm({ ...form, inFlightBatches: value === '' || value === null ? '' : String(value) })}
              />
              <Select
                label="Target loader"
                description="Auto prefers a ready native bulk loader."
                data={[
                  { value: 'AUTO', label: 'Automatic (recommended)' },
                  { value: 'JDBC', label: 'Portable JDBC' },
                  { value: 'NATIVE', label: 'Require native loader' }
                ]}
                value={form.nativeLoadMode}
                disabled={!canConfigure}
                onChange={(value) => setForm({ ...form, nativeLoadMode: value || 'AUTO' })}
              />
              {oracleTarget ? (
                <Select
                  label="Oracle load profile"
                  description="Govern redo, recovery, and SQL*Loader execution."
                  data={[
                    { value: 'AUTO', label: 'Governed automatic' },
                    { value: 'DIRECT_RECOVERABLE', label: 'Direct path - recoverable' },
                    { value: 'DIRECT_MINIMAL_REDO', label: 'Direct path - minimal redo' },
                    { value: 'CONVENTIONAL_SAFE', label: 'Conventional - recoverable' }
                  ]}
                  value={form.oracleLoadProfile}
                  disabled={!canConfigure || form.nativeLoadMode === 'JDBC'}
                  onChange={(value) => setForm({ ...form, oracleLoadProfile: value || 'AUTO' })}
                />
              ) : null}
              <NumberInput
                label="Chunk retries"
                description="Retries only the failed bounded chunk."
                min={1}
                max={10}
                value={form.chunkRetryLimit === '' ? '' : Number(form.chunkRetryLimit)}
                disabled={!canConfigure}
                onChange={(value) => setForm({ ...form, chunkRetryLimit: value === '' || value === null ? '' : String(value) })}
              />
            </SimpleGrid>
            {oracleTarget && form.oracleLoadProfile === 'DIRECT_MINIMAL_REDO' ? (
              <Alert mt="sm" color="orange" icon={<IconAlertTriangle size={16} />}>
                Minimal redo is fail-closed. The runtime must explicitly approve it and the recovery plan must include a post-load backup or reproducible rebuild.
              </Alert>
            ) : null}
          </details>
          {inPlace ? (
            <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
              <Group justify="space-between" align="flex-end" wrap="wrap">
                <Text size="sm">
                  In-place masks the SOURCE tables themselves — no copy is made. With a driver filter or row limits it also deletes
                  the non-subset rows. You will be asked to confirm.
                </Text>
                <TextInput
                  {...technicalInputProps}
                  label="Chunk key (large tables)"
                   placeholder="optional, e.g. id"
                   value={form.inPlaceChunkKey}
                   disabled={!canConfigure}
                  onChange={(e) => setForm({ ...form, inPlaceChunkKey: e.currentTarget.value })}
                />
              </Group>
            </Alert>
          ) : null}
          <Checkbox
            label="Oracle partition exchange (load+mask into a staging table, then swap it into the target partition)"
            checked={form.exchange}
            disabled={!canConfigure}
            onChange={(e) => setForm({ ...form, exchange: e.currentTarget.checked })}
          />
          {form.exchange ? (
            <SimpleGrid cols={{ base: 1, sm: 3 }}>
              <TextInput
                {...technicalInputProps}
                label="Partition name"
                 placeholder="P_2026_07"
                 value={form.exchangePartition}
                 disabled={!canConfigure}
                onChange={(e) => setForm({ ...form, exchangePartition: e.currentTarget.value })}
              />
              <TextInput
                {...technicalInputProps}
                label="Staging table override"
                 placeholder="optional"
                 value={form.exchangeTable}
                 disabled={!canConfigure}
                onChange={(e) => setForm({ ...form, exchangeTable: e.currentTarget.value })}
              />
              <Checkbox
                mt={28}
                 label="WITH VALIDATION"
                 checked={form.exchangeValidate}
                 disabled={!canConfigure}
                onChange={(e) => setForm({ ...form, exchangeValidate: e.currentTarget.checked })}
              />
            </SimpleGrid>
          ) : null}
          <Group className="ds-provision-run-actions" justify="flex-end" gap="xs">
            <Button variant="light" disabled={!canManage || !!busyAction} onClick={openSaveModal}>
              Save as job
            </Button>
            <Button
              leftSection={<IconPlayerPlay size={16} />}
              loading={busyAction === 'launch'}
              disabled={!canProvisionRun || busyAction === 'save'}
              onClick={requestLaunch}
            >
              Launch provision
            </Button>
          </Group>
        </Stack>
      </Paper>
          </Stack>
        </Tabs.Panel>

        <Tabs.Panel value="history" pt="md">
          <Paper className="forge-card" p="md"><ProvisionJobMonitor datasetId={blueprint.id} /></Paper>
        </Tabs.Panel>

        <Tabs.Panel value="saved" pt="md">
          <Paper className="forge-card" p="md">
            <Stack gap="sm">
              <div>
                <Text fw={800}>Saved jobs</Text>
                <Text size="sm" c="dimmed">Reusable, schedulable runs. Load restores settings into Build &amp; launch.</Text>
              </div>
              <SavedJobsPanel
                jobs={savedJobs}
                blueprint={blueprint}
                onLoad={(spec) => {
                  loadFromSpec(spec);
                  setActiveSection('build');
                }}
              />
            </Stack>
          </Paper>
        </Tabs.Panel>
      </Tabs>

      <Modal opened={saveOpened} onClose={() => setSaveOpened(false)} title="Save DataScope job">
        <Stack gap="sm">
          <NameInput label="Saved job name" value={saveName} disabled={!canManage} onChange={(value) => setSaveName(value)} />
          <TextInput label="Description" placeholder="optional" value={saveDescription} disabled={!canManage} onChange={(e) => setSaveDescription(e.currentTarget.value)} />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setSaveOpened(false)}>
              Cancel
            </Button>
            <Button loading={busyAction === 'save'} disabled={!canManage || !saveName.trim() || busyAction === 'launch'} onClick={() => void saveAsJob()}>
              Save
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={driftOverrideOpened}
        onClose={() => { setDriftOverrideOpened(false); setDriftOverrideReason(''); }}
        title="Authorize schema drift override"
        size="md"
      >
        <Stack gap="sm">
          <Alert color="red" icon={<IconAlertTriangle size={16} />}>
            The pre-provision scan will run again. This one-time override is retained in the audit trail and does not change the accepted baseline.
          </Alert>
          <TextInput
            label="Override reason"
            description="Required reviewer evidence; minimum 8 characters."
            value={driftOverrideReason}
            onChange={(event) => setDriftOverrideReason(event.currentTarget.value)}
            maxLength={1000}
            data-autofocus
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => { setDriftOverrideOpened(false); setDriftOverrideReason(''); }}>Cancel</Button>
            <Button
              color="red"
              loading={busyAction === 'launch'}
              disabled={driftOverrideReason.trim().length < 8}
              onClick={() => void launch(driftOverrideReason)}
            >
              Launch with override
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function safeParse(value: unknown): Record<string, unknown> | null {
  if (!value) return null;
  if (typeof value === 'object') return value as Record<string, unknown>;
  try {
    return JSON.parse(String(value)) as Record<string, unknown>;
  } catch {
    return null;
  }
}
