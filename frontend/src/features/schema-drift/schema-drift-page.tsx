'use client';

import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Divider,
  Drawer,
  Group,
  Loader,
  Modal,
  Paper,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
  Tooltip,
  UnstyledButton
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconArrowsDiff,
  IconDatabase,
  IconFileCode,
  IconFileTypeCsv,
  IconPlus,
  IconRefresh,
  IconTrash,
  IconX
} from '@tabler/icons-react';

import { StatusPill } from '@/components/status-pill';
import { SchemaDriftWorkspace } from '@/features/datascope/components/guardrails-panel';
import { useDataSources, useSchemas } from '@/features/datascope/hooks';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DriftIssue, DriftReport, SchemaDriftMonitor } from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import classes from './schema-drift-page.module.css';

const MONITOR_NAME_MIN = 8;
const MONITOR_NAME_MAX = 64;

export function SchemaDriftPage() {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [schemaName, setSchemaName] = useState<string | null>(null);

  const monitorsQuery = useQuery({
    queryKey: keys.schemaDrift.monitors,
    queryFn: () => apiFetch<SchemaDriftMonitor[]>('/api/schema-drift/monitors')
  });
  const monitorQuery = useQuery({
    queryKey: keys.schemaDrift.monitor(selectedId),
    enabled: selectedId !== null,
    queryFn: () => apiFetch<DriftReport>(`/api/schema-drift/monitors/${selectedId}`)
  });
  const sourcesQuery = useDataSources();
  const schemasQuery = useSchemas(sourceId ? Number(sourceId) : null);

  useEffect(() => {
    const monitors = monitorsQuery.data || [];
    if (selectedId !== null && !monitors.some((monitor) => monitor.id === selectedId)) {
      setSelectedId(null);
      setWorkspaceOpen(false);
    }
  }, [monitorsQuery.data, selectedId]);

  const selected = (monitorsQuery.data || []).find((monitor) => monitor.id === selectedId) || null;
  const query = search.trim().toLowerCase();
  const filteredMonitors = useMemo(
    () => (monitorsQuery.data || []).filter((monitor) => !query || [
      monitor.name,
      monitor.dataSourceName,
      monitor.schemaName,
      monitor.engine,
      monitor.report?.status
    ].some((value) => String(value || '').toLowerCase().includes(query))),
    [monitorsQuery.data, query]
  );
  const sourceOptions = (sourcesQuery.data || [])
    .filter((source) => ['SOURCE', 'BOTH'].includes(String(source.role || '').toUpperCase()))
    .map((source) => ({ value: String(source.id), label: `${source.name} / ${source.kind}` }));
  const schemaOptions = (schemasQuery.data || [])
    .map((schema) => String(schema.schema || schema.name || '').trim())
    .filter(Boolean)
    .map((schema) => ({ value: schema, label: schema }));
  const cleanName = name.trim();
  const createValid = cleanName.length >= MONITOR_NAME_MIN
    && cleanName.length <= MONITOR_NAME_MAX
    && Boolean(sourceId)
    && Boolean(schemaName);

  const resetCreate = () => {
    setName('');
    setDescription('');
    setSourceId(null);
    setSchemaName(null);
  };

  const createMonitor = async () => {
    if (!createValid) return;
    setBusy('create');
    try {
      const created = await apiPost<SchemaDriftMonitor>('/api/schema-drift/monitors', {
        name: cleanName,
        description: description.trim() || null,
        dataSourceId: Number(sourceId),
        schemaName
      });
      await queryClient.invalidateQueries({ queryKey: keys.schemaDrift.monitors });
      setSelectedId(created.id);
      setWorkspaceOpen(true);
      setCreateOpen(false);
      resetCreate();
      notifications.show({
        color: 'green',
        title: 'Schema monitor created',
        message: 'Capture an accepted baseline when this schema is in its intended state.'
      });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not create monitor', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const deleteMonitor = async () => {
    if (!selected) return;
    setBusy('delete');
    try {
      await apiFetch<void>(`/api/schema-drift/monitors/${selected.id}`, { method: 'DELETE' });
      queryClient.removeQueries({ queryKey: keys.schemaDrift.monitor(selected.id) });
      setSelectedId(null);
      setWorkspaceOpen(false);
      await queryClient.invalidateQueries({ queryKey: keys.schemaDrift.monitors });
      setDeleteOpen(false);
      notifications.show({ color: 'green', title: 'Monitor deleted', message: 'Its retained baselines and scan evidence were removed.' });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete monitor', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: keys.schemaDrift.monitors }),
      selectedId ? queryClient.invalidateQueries({ queryKey: keys.schemaDrift.monitor(selectedId) }) : Promise.resolve()
    ]);
  };

  const exportEvidence = (format: 'json' | 'csv') => {
    if (!selected || !monitorQuery.data) return;
    const report = monitorQuery.data;
    const content = format === 'json'
      ? JSON.stringify({ monitor: selected, report }, null, 2)
      : issuesCsv(report.issues || []);
    const blob = new Blob([content], { type: format === 'json' ? 'application/json' : 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${fileName(selected.name)}-schema-drift.${format}`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const openMonitor = (id: number) => {
    setSelectedId(id);
    setWorkspaceOpen(true);
  };

  return (
    <main className={`forge-page ${classes.page}`}>
      <header className={classes.pageHeader}>
        <Group gap="sm" wrap="nowrap" align="flex-start">
          <span className={classes.pageMark}><IconArrowsDiff size={21} /></span>
          <div>
            <Group gap="sm" align="center">
              <Title order={1} size="h2">Schema Drift</Title>
              <span className={classes.lifecycle}>Baseline / Compare / Govern</span>
            </Group>
            <Text c="dimmed" size="sm">Detect structural changes before they break masking, subsetting, or delivery.</Text>
          </div>
        </Group>
        <Group gap="xs">
          <Tooltip label="Refresh retained state">
            <ActionIcon variant="light" size="lg" aria-label="Refresh schema monitors" onClick={() => void refresh()}>
              <IconRefresh size={18} />
            </ActionIcon>
          </Tooltip>
          {canManage ? <Button leftSection={<IconPlus size={17} />} onClick={() => setCreateOpen(true)}>New monitor</Button> : null}
        </Group>
      </header>

      <Paper className={`forge-card ${classes.inventory}`} p={0}>
        <div className={classes.inventoryToolbar}>
          <div>
            <Group gap="xs">
              <Text fw={800}>Monitored schemas</Text>
              <Badge variant="light">{monitorsQuery.data?.length || 0}</Badge>
            </Group>
            <Text size="sm" c="dimmed">Open a schema only when you need its comparison, schedule, history, or governance actions.</Text>
          </div>
          <TextInput
            placeholder="Find monitor, source, engine, or schema"
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            spellCheck={false}
            autoCorrect="off"
            className={classes.inventorySearch}
          />
        </div>
        <div className={classes.inventoryHead} aria-hidden="true">
          <span>Monitor</span><span>Connection</span><span>Schema</span><span>Baseline</span><span>Changes</span><span>Status</span><span />
        </div>
        <div className={classes.monitorList}>
          {monitorsQuery.isLoading ? <Group justify="center" py="xl"><Loader size="sm" /><Text c="dimmed">Loading schema monitors...</Text></Group> : null}
          {filteredMonitors.map((monitor) => {
            const report = monitor.report;
            const changes = report?.summary?.issueCount ?? report?.issues?.length ?? 0;
            return <UnstyledButton key={monitor.id} className={classes.monitorRow} onClick={() => openMonitor(monitor.id)}>
              <div className={classes.monitorIdentity}>
                <Text fw={760} size="sm" truncate>{monitor.name}</Text>
                <Text size="xs" c="dimmed" truncate>{monitor.description || 'Schema structure monitor'}</Text>
              </div>
              <div><Text size="sm" fw={650} truncate>{monitor.dataSourceName}</Text><Text size="xs" c="dimmed">{monitor.engine}</Text></div>
              <Text size="sm" fw={650} truncate>{monitor.schemaName}</Text>
              <Text size="sm" fw={700}>{report?.baseline ? `v${report.baseline.version}` : 'Required'}</Text>
              <div><Text size="sm" fw={700}>{changes}</Text><Text size="xs" c={report?.blockingCount ? 'red' : 'dimmed'}>{report?.blockingCount || 0} blocking</Text></div>
              <div><StatusPill value={report?.status} /><Text size="xs" c="dimmed" mt={3}>{shortDate(report?.checkedAt)}</Text></div>
              <Text size="sm" fw={750} c="blue" ta="right">Open workspace</Text>
            </UnstyledButton>;
          })}
          {!monitorsQuery.isLoading && !filteredMonitors.length ? (
            <div className={classes.empty}>
              <Stack align="center" gap="xs" maw={430}>
                <IconArrowsDiff size={38} color="var(--mantine-color-dimmed)" />
                <Title order={2} size="h3">{query ? 'No matching monitors' : 'Monitor a database schema'}</Title>
                <Text c="dimmed">{query ? 'Clear the filter or search another source or schema.' : 'Create a monitor, capture the intended structure once, then scan manually or on a schedule.'}</Text>
                {!query && canManage ? <Button mt="sm" leftSection={<IconPlus size={17} />} onClick={() => setCreateOpen(true)}>New monitor</Button> : null}
              </Stack>
            </div>
          ) : null}
        </div>
      </Paper>

      <Modal
        opened={workspaceOpen && Boolean(selected)}
        onClose={() => setWorkspaceOpen(false)}
        fullScreen
        withCloseButton={false}
        padding={0}
        zIndex={360}
        classNames={{ content: classes.workspaceModal, body: classes.workspaceModalBody }}
      >
        {selected ? <div className={classes.workspaceShell}>
          <header className={classes.workspaceHeader}>
            <Group gap="sm" wrap="nowrap" className={classes.workspaceTitle}>
              <span className={classes.pageMark}><IconDatabase size={20} /></span>
              <div>
                <Group gap="xs"><Title order={2} size="h3">{selected.name}</Title><StatusPill value={monitorQuery.data?.status || selected.report?.status} /></Group>
                <Text size="sm" c="dimmed">{selected.dataSourceName} / {selected.engine} / {selected.schemaName}</Text>
              </div>
            </Group>
            <Group gap="xs" wrap="nowrap">
              <Tooltip label="Download JSON evidence"><ActionIcon variant="default" aria-label="Download JSON evidence" onClick={() => exportEvidence('json')} disabled={!monitorQuery.data}><IconFileCode size={18} /></ActionIcon></Tooltip>
              <Tooltip label="Download issue CSV"><ActionIcon variant="default" aria-label="Download issue CSV" onClick={() => exportEvidence('csv')} disabled={!monitorQuery.data}><IconFileTypeCsv size={18} /></ActionIcon></Tooltip>
              {canManage ? <Tooltip label="Delete monitor"><ActionIcon color="red" variant="subtle" aria-label="Delete monitor" onClick={() => setDeleteOpen(true)}><IconTrash size={18} /></ActionIcon></Tooltip> : null}
              <Tooltip label="Close workspace"><ActionIcon variant="subtle" color="gray" size="lg" aria-label="Close schema drift workspace" onClick={() => setWorkspaceOpen(false)}><IconX size={20} /></ActionIcon></Tooltip>
            </Group>
          </header>
          <div className={classes.workspaceBody}>
            {monitorQuery.isLoading ? <Paper className="forge-card" p="xl"><Group justify="center"><Loader size="sm" /><Text c="dimmed">Loading retained evidence...</Text></Group></Paper> : null}
            {monitorQuery.isError ? <Alert color="red" title="Could not load schema monitor">{monitorQuery.error.message}</Alert> : null}
            {monitorQuery.data ? <SchemaDriftWorkspace
              datasetId={selected.id}
              drift={monitorQuery.data}
              endpointBase={`/api/schema-drift/monitors/${selected.id}`}
              heading="Structural evidence"
              description="Compare live tables, columns, keys, relationships, indexes, defaults, and supported CHECK constraints with the accepted baseline."
            /> : null}
          </div>
        </div> : null}
      </Modal>

      <Drawer opened={createOpen} onClose={() => { setCreateOpen(false); resetCreate(); }} title="New schema monitor" position="right" size="md">
        <Stack gap="md">
          <Alert color="blue" icon={<IconArrowsDiff size={17} />}>
            This monitors every table in one physical schema. No data rows are read and nothing is changed in the source database.
          </Alert>
          <TextInput
            label="Monitor name"
            description={`${MONITOR_NAME_MIN}-${MONITOR_NAME_MAX} characters`}
            placeholder="Core banking schema"
            value={name}
            onChange={(event) => setName(event.currentTarget.value)}
            maxLength={MONITOR_NAME_MAX}
            spellCheck={false}
            data-autofocus
          />
          <Textarea label="Description" placeholder="Purpose, owner, or downstream jobs affected by this schema" value={description} onChange={(event) => setDescription(event.currentTarget.value)} maxLength={500} autosize minRows={2} maxRows={4} />
          <Select
            label="Data source"
            placeholder="Search source-capable connections"
            data={sourceOptions}
            value={sourceId}
            onChange={(value) => { setSourceId(value); setSchemaName(null); }}
            searchable
            nothingFoundMessage="No source-capable connection found"
          />
          <Select
            label="Schema"
            placeholder={sourceId ? 'Search schemas' : 'Choose a data source first'}
            data={schemaOptions}
            value={schemaName}
            onChange={setSchemaName}
            disabled={!sourceId}
            searchable
            allowDeselect={false}
            rightSection={schemasQuery.isFetching ? <Loader size={14} /> : undefined}
            nothingFoundMessage="No schema found"
          />
          <Divider />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => { setCreateOpen(false); resetCreate(); }}>Discard</Button>
            <Button loading={busy === 'create'} disabled={!createValid} onClick={() => void createMonitor()}>Create monitor</Button>
          </Group>
        </Stack>
      </Drawer>

      <Modal opened={deleteOpen} onClose={() => setDeleteOpen(false)} title="Delete schema monitor" centered>
        <Stack gap="md">
          <Alert color="red" title="This removes retained evidence">
            Delete <b>{selected?.name}</b>, including every accepted baseline, scan run, and schedule. The source schema is never changed.
          </Alert>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDeleteOpen(false)}>Keep monitor</Button>
            <Button color="red" loading={busy === 'delete'} onClick={() => void deleteMonitor()}>Delete monitor</Button>
          </Group>
        </Stack>
      </Modal>
    </main>
  );
}

function issuesCsv(issues: DriftIssue[]) {
  const rows = [
    ['Severity', 'Change', 'Scope', 'Data source', 'Schema', 'Table', 'Column', 'Detail', 'Before', 'After'],
    ...issues.map((issue) => [
      issue.severity,
      issue.type,
      issue.scope,
      issue.dataSourceId,
      issue.schema,
      issue.table,
      issue.column,
      issue.detail,
      issue.beforeValue,
      issue.afterValue
    ])
  ];
  return rows.map((row) => row.map(csvCell).join(',')).join('\r\n');
}

function csvCell(value: unknown) {
  return `"${String(value ?? '').replaceAll('"', '""')}"`;
}

function fileName(value: string) {
  return value.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'schema-monitor';
}

function shortDate(value?: string | null) {
  if (!value) return 'Not checked';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}
