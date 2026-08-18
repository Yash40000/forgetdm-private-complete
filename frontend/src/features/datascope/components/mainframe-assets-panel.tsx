'use client';

import { useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Modal,
  MultiSelect,
  NumberInput,
  Paper,
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
import { IconEdit, IconFileText, IconLink, IconPlayerPlay, IconPlus, IconSearch, IconTrash } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type {
  DataScopeMainframeAsset,
  DataScopeMainframeFieldMapping,
  MaskingPolicy,
  ResolvedMainframeFile
} from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import { useCopybookFields, useMainframeConnections, useMainframeCopybooks } from '@/features/mainframe/hooks';
import { usePolicyRules } from '@/features/datascope/hooks';

type AssetForm = {
  id: number | null;
  logicalRole: string;
  sourceConnectionId: string | null;
  targetConnectionId: string | null;
  sourceNamePattern: string;
  targetNameTemplate: string;
  copybookId: string | null;
  dsorg: string;
  recfm: string;
  lrecl: number | string;
  codePage: string;
  selectionMode: string;
  keyFields: string[];
  entityKeyFieldPath: string | null;
  filterExpression: string;
  enabled: boolean;
};

type MappingDraft = { fieldPath: string | null; policyRuleId: string | null };

const EMPTY_FORM: AssetForm = {
  id: null,
  logicalRole: '',
  sourceConnectionId: null,
  targetConnectionId: null,
  sourceNamePattern: '',
  targetNameTemplate: '',
  copybookId: null,
  dsorg: 'PS',
  recfm: 'FB',
  lrecl: '',
  codePage: 'Cp037',
  selectionMode: 'ALL',
  keyFields: [],
  entityKeyFieldPath: null,
  filterExpression: '',
  enabled: true
};

export function MainframeAssetsPanel({
  datasetId,
  assets,
  loading,
  policies,
  defaultPolicyId
}: {
  datasetId: number;
  assets: DataScopeMainframeAsset[];
  loading: boolean;
  policies: MaskingPolicy[];
  defaultPolicyId: number | null;
}) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const canRun = can('provision.run');
  const connectionsQuery = useMainframeConnections();
  const copybooksQuery = useMainframeCopybooks();
  const [form, setForm] = useState<AssetForm>(EMPTY_FORM);
  const [editorOpened, setEditorOpened] = useState(false);
  const [resolved, setResolved] = useState<ResolvedMainframeFile[]>([]);
  const [resolvedTitle, setResolvedTitle] = useState('Resolved files');
  const [resolveOpened, setResolveOpened] = useState(false);
  const [mappingOpened, setMappingOpened] = useState(false);
  const [mappingAsset, setMappingAsset] = useState<DataScopeMainframeAsset | null>(null);
  const [mappingPolicyId, setMappingPolicyId] = useState<string | null>(defaultPolicyId ? String(defaultPolicyId) : null);
  const [mappingRows, setMappingRows] = useState<MappingDraft[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const copybookId = form.copybookId ? Number(form.copybookId) : null;
  const fieldsQuery = useCopybookFields(copybookId);
  const mappingPolicyNumber = mappingPolicyId ? Number(mappingPolicyId) : null;
  const policyRulesQuery = usePolicyRules(mappingPolicyNumber, mappingOpened);
  const mappingFieldsQuery = useCopybookFields(mappingAsset?.copybookId || null);

  const connections = connectionsQuery.data || [];
  const copybooks = copybooksQuery.data || [];
  const connectionOptions = connections.map((item) => ({
    value: String(item.id),
    label: `${item.name} (${item.type})`
  }));
  const copybookOptions = copybooks.map((item) => ({
    value: String(item.id),
    label: `${item.name}${item.recordLength ? ` / ${item.recordLength} bytes` : ''}`
  }));
  const fieldOptions = (fieldsQuery.data || []).map((field) => ({ value: field.path, label: field.path }));
  const connectionById = useMemo(() => new Map(connections.map((item) => [item.id, item.name])), [connections]);
  const copybookById = useMemo(() => new Map(copybooks.map((item) => [item.id, item.name])), [copybooks]);
  const policyOptions = policies.map((policy) => ({ value: String(policy.id), label: policy.name }));
  const mappingFieldOptions = (mappingFieldsQuery.data || []).map((field) => ({ value: field.path, label: field.path }));
  const policyRuleOptions = (policyRulesQuery.data || []).map((rule) => ({
    value: String(rule.id),
    label: `${rule.schemaName ? `${rule.schemaName}.` : ''}${rule.tableName}.${rule.columnName} → ${rule.function}${rule.semanticSalt ? ` [${rule.semanticSalt}]` : ''}`
  }));

  const openCreate = () => {
    setForm({ ...EMPTY_FORM, keyFields: [] });
    setEditorOpened(true);
  };

  const openEdit = (asset: DataScopeMainframeAsset) => {
    setForm({
      id: asset.id || null,
      logicalRole: asset.logicalRole || '',
      sourceConnectionId: asset.sourceConnectionId ? String(asset.sourceConnectionId) : null,
      targetConnectionId: asset.targetConnectionId ? String(asset.targetConnectionId) : null,
      sourceNamePattern: asset.sourceNamePattern || '',
      targetNameTemplate: asset.targetNameTemplate || '',
      copybookId: asset.copybookId ? String(asset.copybookId) : null,
      dsorg: asset.dsorg || 'PS',
      recfm: asset.recfm || 'FB',
      lrecl: asset.lrecl || '',
      codePage: asset.codePage || 'Cp037',
      selectionMode: asset.selectionMode || 'ALL',
      keyFields: String(asset.keyFieldPaths || '').split(',').map((value) => value.trim()).filter(Boolean),
      entityKeyFieldPath: asset.entityKeyFieldPath || null,
      filterExpression: asset.filterExpression || '',
      enabled: asset.enabled !== false
    });
    setEditorOpened(true);
  };

  const save = async () => {
    if (!canManage || busy) return;
    if (!form.logicalRole.trim() || !form.sourceConnectionId || !form.copybookId || !form.sourceNamePattern.trim()) {
      notifications.show({ color: 'red', title: 'File definition incomplete', message: 'Role, source connection, source name/pattern, and copybook are required.' });
      return;
    }
    setBusy('save');
    const payload: DataScopeMainframeAsset = {
      logicalRole: form.logicalRole.trim(),
      sourceConnectionId: Number(form.sourceConnectionId),
      targetConnectionId: form.targetConnectionId ? Number(form.targetConnectionId) : null,
      sourceNamePattern: form.sourceNamePattern.trim(),
      targetNameTemplate: form.targetNameTemplate.trim() || null,
      copybookId: Number(form.copybookId),
      dsorg: form.dsorg,
      recfm: form.recfm,
      lrecl: typeof form.lrecl === 'number' && form.lrecl > 0 ? form.lrecl : null,
      codePage: form.codePage.trim() || null,
      selectionMode: form.selectionMode,
      keyFieldPaths: form.keyFields.join(',') || null,
      entityKeyFieldPath: form.entityKeyFieldPath || null,
      filterExpression: form.filterExpression.trim() || null,
      enabled: form.enabled,
      ordinalNo: form.id ? assets.find((item) => item.id === form.id)?.ordinalNo || 0 : assets.length
    };
    try {
      if (form.id) await apiPut(`/api/datasets/${datasetId}/mainframe-assets/${form.id}`, payload);
      else await apiPost(`/api/datasets/${datasetId}/mainframe-assets`, payload);
      notifications.show({ color: 'green', title: form.id ? 'File asset updated' : 'File asset added', message: payload.logicalRole });
      setEditorOpened(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.datascope.mainframeAssets(datasetId) }),
        queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints })
      ]);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not save file asset', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const remove = async (asset: DataScopeMainframeAsset) => {
    if (!canManage || !asset.id) return;
    const ok = await confirm({
      title: 'Remove mainframe file asset',
      message: `Remove "${asset.logicalRole}" from this DataScope? Source and target files are not deleted.`,
      okText: 'Remove asset',
      danger: true
    });
    if (!ok) return;
    setBusy(`delete-${asset.id}`);
    try {
      await apiFetch(`/api/datasets/${datasetId}/mainframe-assets/${asset.id}`, { method: 'DELETE' });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.mainframeAssets(datasetId) });
      notifications.show({ color: 'green', title: 'File asset removed', message: asset.logicalRole });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not remove file asset', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const resolve = async (asset: DataScopeMainframeAsset) => {
    if (!asset.id || busy) return;
    setBusy(`resolve-${asset.id}`);
    try {
      const rows = await apiPost<ResolvedMainframeFile[]>(`/api/datasets/${datasetId}/mainframe-assets/${asset.id}/resolve`, {});
      setResolved(rows);
      setResolvedTitle(`${asset.logicalRole}: ${rows.length} resolved file${rows.length === 1 ? '' : 's'}`);
      setResolveOpened(true);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not resolve file pattern', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const loadMappings = async (asset: DataScopeMainframeAsset, policyValue: string | null) => {
    setMappingAsset(asset);
    setMappingPolicyId(policyValue);
    setMappingRows([]);
    if (!asset.id || !policyValue) return;
    setBusy('mapping-load');
    try {
      const rows = await apiFetch<DataScopeMainframeFieldMapping[]>(
        `/api/datasets/${datasetId}/mainframe-assets/${asset.id}/field-mappings?policyId=${policyValue}`
      );
      setMappingRows(rows.map((row) => ({
        fieldPath: row.fieldPath,
        policyRuleId: row.policyRuleId ? String(row.policyRuleId) : null
      })));
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not load policy mappings', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const openMappings = (asset: DataScopeMainframeAsset) => {
    const policyValue = defaultPolicyId
      ? String(defaultPolicyId)
      : policies.length === 1 ? String(policies[0].id) : null;
    setMappingOpened(true);
    void loadMappings(asset, policyValue);
  };

  const addMapping = () => {
    const used = new Set(mappingRows.map((row) => row.fieldPath).filter(Boolean));
    const available = mappingFieldOptions.find((field) => !used.has(field.value));
    setMappingRows([...mappingRows, { fieldPath: available?.value || null, policyRuleId: null }]);
  };

  const saveMappings = async () => {
    if (!canManage || !mappingAsset?.id || !mappingPolicyId || busy) return;
    if (mappingRows.some((row) => !row.fieldPath || !row.policyRuleId)) {
      notifications.show({ color: 'red', title: 'Mapping incomplete', message: 'Every row needs a copybook field and a database policy rule.' });
      return;
    }
    if (new Set(mappingRows.map((row) => row.fieldPath)).size !== mappingRows.length) {
      notifications.show({ color: 'red', title: 'Duplicate file field', message: 'Each copybook field can be mapped only once per policy.' });
      return;
    }
    setBusy('mapping-save');
    try {
      await apiPut<DataScopeMainframeFieldMapping[]>(
        `/api/datasets/${datasetId}/mainframe-assets/${mappingAsset.id}/field-mappings?policyId=${mappingPolicyId}`,
        mappingRows.map((row, index) => ({
          fieldPath: row.fieldPath as string,
          policyId: Number(mappingPolicyId),
          policyRuleId: Number(row.policyRuleId),
          ordinalNo: index
        }))
      );
      await queryClient.invalidateQueries({
        queryKey: keys.datascope.mainframeFieldMappings(mappingAsset.id, Number(mappingPolicyId))
      });
      notifications.show({
        color: 'green',
        title: 'Governed file masking saved',
        message: `${mappingRows.length} copybook field${mappingRows.length === 1 ? '' : 's'} bound to the database policy.`
      });
      setMappingOpened(false);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not save policy mappings', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const run = async () => {
    if (!canRun || busy) return;
    setBusy('run');
    try {
      const result = await apiPost<{ runGroupId?: string; fileCount?: number; message?: string }>(
        `/api/datasets/${datasetId}/mainframe-assets/run`,
        { name: `DataScope ${datasetId} mainframe run`, policyId: defaultPolicyId }
      );
      await queryClient.invalidateQueries({ queryKey: keys.mainframe.jobs });
      notifications.show({ color: 'green', title: 'Mainframe run submitted', message: result.message || `${result.fileCount || 0} files queued` });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not launch mainframe run', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Stack gap="md">
      {confirmElement}
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={3}>Mainframe file assets</Title>
          <Text size="sm" c="dimmed">Attach multiple copybook-driven files. DataScope stores metadata only; file bytes stream between configured endpoints.</Text>
        </div>
        <Group gap="xs">
          {canRun ? <Button variant="light" leftSection={<IconPlayerPlay size={15} />} loading={busy === 'run'} disabled={!assets.some((item) => item.enabled !== false)} onClick={() => void run()}>Run enabled files</Button> : null}
          {canManage ? <Button leftSection={<IconPlus size={15} />} onClick={openCreate}>Add file</Button> : null}
        </Group>
      </Group>

      <Alert color="blue" icon={<IconFileText size={16} />}>
        File primary keys are one or more copybook fields. The Business Entity key mapping is configured separately so physical file identity can differ from the canonical customer/account identity.
      </Alert>
      {!defaultPolicyId ? <Alert color="yellow">Select a default masking policy on this DataScope, then map each copybook field to one of that policy&apos;s database rules. File execution fails closed without a governed policy.</Alert> : null}

      <Paper withBorder>
        <Table highlightOnHover verticalSpacing="sm" horizontalSpacing="md">
          <Table.Thead>
            <Table.Tr><Table.Th>Role / source</Table.Th><Table.Th>Copybook</Table.Th><Table.Th>File PK</Table.Th><Table.Th>Format</Table.Th><Table.Th>Target</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {assets.map((asset) => (
              <Table.Tr key={asset.id || asset.logicalRole}>
                <Table.Td><Text fw={750}>{asset.logicalRole}</Text><Text size="xs" c="dimmed" ff="monospace">{asset.sourceNamePattern}</Text><Text size="xs" c="dimmed">{connectionById.get(asset.sourceConnectionId || -1) || `Connection #${asset.sourceConnectionId || '?'}`}</Text></Table.Td>
                <Table.Td>{copybookById.get(asset.copybookId || -1) || `Copybook #${asset.copybookId || '?'}`}</Table.Td>
                <Table.Td><Text size="xs" ff="monospace">{asset.keyFieldPaths || 'Not defined'}</Text>{asset.entityKeyFieldPath ? <Text size="xs" c="dimmed">Entity: {asset.entityKeyFieldPath}</Text> : null}</Table.Td>
                <Table.Td><Badge variant="outline">{asset.dsorg || 'PS'} / {asset.recfm || 'FB'}</Badge><Text size="xs" c="dimmed">LRECL {asset.lrecl || 'copybook'} · {asset.codePage || 'Cp037'}</Text></Table.Td>
                <Table.Td><Text size="xs">{connectionById.get(asset.targetConnectionId || -1) || 'Not configured'}</Text><Text size="xs" c="dimmed" ff="monospace">{asset.targetNameTemplate || 'Same as source'}</Text></Table.Td>
                <Table.Td><Badge color={asset.enabled === false ? 'gray' : asset.selectionMode === 'ALL' ? 'green' : 'yellow'} variant="light">{asset.enabled === false ? 'DISABLED' : asset.selectionMode || 'ALL'}</Badge></Table.Td>
                <Table.Td>
                  <Group gap={4} justify="flex-end" wrap="nowrap">
                    <Button size="compact-xs" variant="subtle" leftSection={<IconSearch size={13} />} loading={busy === `resolve-${asset.id}`} onClick={() => void resolve(asset)}>Resolve</Button>
                    {canManage ? <Button size="compact-xs" variant="subtle" leftSection={<IconLink size={13} />} onClick={() => openMappings(asset)}>Policy map</Button> : null}
                    {canManage ? <Button size="compact-xs" variant="subtle" leftSection={<IconEdit size={13} />} onClick={() => openEdit(asset)}>Edit</Button> : null}
                    {canManage ? <Button size="compact-xs" variant="subtle" color="red" leftSection={<IconTrash size={13} />} loading={busy === `delete-${asset.id}`} onClick={() => void remove(asset)}>Remove</Button> : null}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
            {!assets.length ? <Table.Tr><Table.Td colSpan={7}><Text ta="center" c="dimmed" py="xl">No mainframe files are attached to this DataScope.</Text></Table.Td></Table.Tr> : null}
          </Table.Tbody>
        </Table>
      </Paper>

      <Modal opened={editorOpened} onClose={() => setEditorOpened(false)} title={form.id ? 'Edit mainframe file asset' : 'Add mainframe file asset'} size="xl">
        <Stack gap="sm">
          <SimpleGrid cols={{ base: 1, md: 2 }}>
            <TextInput label="Logical role" placeholder="customer-master-file" value={form.logicalRole} onChange={(event) => setForm({ ...form, logicalRole: event.currentTarget.value })} required />
            <Select label="Copybook" data={copybookOptions} value={form.copybookId} searchable required onChange={(value) => setForm({ ...form, copybookId: value, keyFields: [], entityKeyFieldPath: null })} />
            <Select label="Source mainframe connection" data={connectionOptions} value={form.sourceConnectionId} searchable required onChange={(value) => setForm({ ...form, sourceConnectionId: value })} />
            <Select label="Target mainframe connection" data={connectionOptions} value={form.targetConnectionId} searchable clearable onChange={(value) => setForm({ ...form, targetConnectionId: value })} />
            <TextInput label="Source dataset name or pattern" description="Examples: HLQ.CUST.MASTER or HLQ.CUST.*" value={form.sourceNamePattern} onChange={(event) => setForm({ ...form, sourceNamePattern: event.currentTarget.value })} required />
            <TextInput label="Target name template" description="Use ${source} when a pattern can resolve multiple files." placeholder="TEST.${source}" value={form.targetNameTemplate} onChange={(event) => setForm({ ...form, targetNameTemplate: event.currentTarget.value })} />
            <Select label="Dataset organization" data={['PS', 'PDS_MEMBER', 'GDG_GENERATION', 'VSAM_EXPORT']} value={form.dsorg} onChange={(value) => setForm({ ...form, dsorg: value || 'PS' })} />
            <Select label="Record format" data={['F', 'FB', 'V', 'VB']} value={form.recfm} onChange={(value) => setForm({ ...form, recfm: value || 'FB' })} />
            <NumberInput label="LRECL" description="Blank uses the copybook record length." min={1} value={form.lrecl} onChange={(value) => setForm({ ...form, lrecl: value })} />
            <TextInput label="EBCDIC code page" value={form.codePage} onChange={(event) => setForm({ ...form, codePage: event.currentTarget.value })} />
          </SimpleGrid>

          <MultiSelect label="File primary-key fields" description="Choose one or more copybook fields; order defines a composite key." data={fieldOptions} value={form.keyFields} searchable disabled={!copybookId} onChange={(value) => setForm({ ...form, keyFields: value })} />
          <Select label="Business Entity key field" description="Optional canonical join field; it may differ from the file PK." data={fieldOptions} value={form.entityKeyFieldPath} searchable clearable disabled={!copybookId} onChange={(value) => setForm({ ...form, entityKeyFieldPath: value })} />
          <Select label="Record selection" data={[{ value: 'ALL', label: 'All records (executable)' }, { value: 'ENTITY_KEYS', label: 'Matching Business Entity keys (model only)' }, { value: 'FILTER', label: 'Copybook-field filter (model only)' }]} value={form.selectionMode} onChange={(value) => setForm({ ...form, selectionMode: value || 'ALL' })} />
          {form.selectionMode === 'FILTER' ? <TextInput label="Filter expression" placeholder="STATUS-CODE = 'A'" value={form.filterExpression} onChange={(event) => setForm({ ...form, filterExpression: event.currentTarget.value })} /> : null}
          {form.dsorg === 'VSAM_EXPORT' ? <Alert color="yellow">VSAM is retained as an export definition. Execution remains blocked until the IDCAMS unload/reload adapter is enabled.</Alert> : null}
          {form.dsorg === 'GDG_GENERATION' ? <Alert color="blue">Use an exact relative generation such as HLQ.BASE(0) for input. Creating a new (+1) target remains fail-closed until generation allocation is certified on the target z/OSMF.</Alert> : null}
          {form.selectionMode !== 'ALL' ? <Alert color="yellow">This selector is saved for the entity-aware phase, but execution currently fails closed instead of masking the wrong records.</Alert> : null}
          <Switch label="Enabled for DataScope and Business Entity runs" checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.currentTarget.checked })} />
          <Group justify="flex-end"><Button variant="default" onClick={() => setEditorOpened(false)}>Cancel</Button><Button loading={busy === 'save'} onClick={() => void save()}>Save file asset</Button></Group>
        </Stack>
      </Modal>

      <Modal opened={mappingOpened} onClose={() => setMappingOpened(false)} title={`Policy field mapping${mappingAsset ? ` · ${mappingAsset.logicalRole}` : ''}`} size="xl">
        <Stack gap="sm">
          <Alert color="blue" icon={<IconLink size={16} />}>
            Choose the same rule that masks the corresponding database column. Its function, parameters, deterministic seed, and semantic salt are frozen into each file job so table and file values remain identical.
          </Alert>
          <Select
            label="Governed masking policy"
            data={policyOptions}
            value={mappingPolicyId}
            searchable
            required
            onChange={(value) => mappingAsset && void loadMappings(mappingAsset, value)}
          />
          {mappingRows.map((row, index) => (
            <Paper key={`${index}-${row.fieldPath || 'new'}`} withBorder p="sm">
              <Group align="flex-end" wrap="nowrap">
                <Select
                  style={{ flex: 1 }}
                  label={index === 0 ? 'Copybook field' : undefined}
                  data={mappingFieldOptions}
                  value={row.fieldPath}
                  searchable
                  required
                  onChange={(value) => setMappingRows(mappingRows.map((item, rowIndex) => rowIndex === index ? { ...item, fieldPath: value } : item))}
                />
                <Select
                  style={{ flex: 2 }}
                  label={index === 0 ? 'Database policy rule (canonical semantic)' : undefined}
                  data={policyRuleOptions}
                  value={row.policyRuleId}
                  searchable
                  required
                  onChange={(value) => setMappingRows(mappingRows.map((item, rowIndex) => rowIndex === index ? { ...item, policyRuleId: value } : item))}
                />
                <Button color="red" variant="subtle" px="xs" onClick={() => setMappingRows(mappingRows.filter((_, rowIndex) => rowIndex !== index))}><IconTrash size={15} /></Button>
              </Group>
            </Paper>
          ))}
          {!mappingRows.length ? <Text size="sm" c="dimmed" ta="center" py="md">No copybook fields are mapped for this policy.</Text> : null}
          <Group justify="space-between">
            <Button variant="light" leftSection={<IconPlus size={14} />} disabled={!mappingPolicyId || !mappingFieldOptions.length} onClick={addMapping}>Add field mapping</Button>
            <Group><Button variant="default" onClick={() => setMappingOpened(false)}>Cancel</Button><Button loading={busy === 'mapping-save'} disabled={!mappingPolicyId} onClick={() => void saveMappings()}>Save governed mappings</Button></Group>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={resolveOpened} onClose={() => setResolveOpened(false)} title={resolvedTitle} size="xl">
        <Table striped highlightOnHover><Table.Thead><Table.Tr><Table.Th>Source</Table.Th><Table.Th>Target</Table.Th><Table.Th>Format</Table.Th><Table.Th>Bytes</Table.Th></Table.Tr></Table.Thead><Table.Tbody>{resolved.map((file) => <Table.Tr key={`${file.assetId}-${file.sourceName}`}><Table.Td ff="monospace">{file.sourceName}</Table.Td><Table.Td ff="monospace">{file.targetName}</Table.Td><Table.Td>{file.recfm} / {file.lrecl || '-'}</Table.Td><Table.Td>{file.sizeBytes?.toLocaleString() || '-'}</Table.Td></Table.Tr>)}</Table.Tbody></Table>
      </Modal>
    </Stack>
  );
}
