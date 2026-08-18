'use client';

import { useMemo, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Loader,
  Paper,
  Select,
  Table,
  Text,
  ThemeIcon,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconChevronLeft,
  IconChevronRight,
  IconDatabase,
  IconEdit,
  IconPlus,
  IconRefresh,
  IconSql,
  IconTable,
  IconTrash
} from '@tabler/icons-react';

import { useConfirm } from '@/components/confirm';
import { usePermissions } from '@/lib/use-permissions';
import { useExplorerCatalog, useExplorerMutations, useExplorerTable } from './hooks';
import { QueryConsoleDrawer } from './components/query-console-drawer';
import { RowEditorDrawer } from './components/row-editor-drawer';
import type { ExplorerColumn, QueryResult, TableIdentity } from './types';

const PAGE_SIZE = 100;

export function DataExplorerPage() {
  const [dataSourceId, setDataSourceId] = useState<number | null>(null);
  const [schema, setSchema] = useState<string | null>(null);
  const [table, setTable] = useState<string | null>(null);
  const [offset, setOffset] = useState(0);
  const [editor, setEditor] = useState<'insert' | 'edit' | null>(null);
  const [selectedRow, setSelectedRow] = useState<Record<string, unknown> | null>(null);
  const [sqlOpened, setSqlOpened] = useState(false);
  const [sqlResult, setSqlResult] = useState<QueryResult | undefined>();
  const permissions = usePermissions();
  const canWrite = permissions.can('datasource.manage');
  const { confirm, confirmElement } = useConfirm();
  const catalog = useExplorerCatalog(dataSourceId, schema);
  const identity: TableIdentity | null =
    dataSourceId && schema && table ? { dataSourceId, schema, table } : null;
  const tableQuery = useExplorerTable(identity, offset, PAGE_SIZE);
  const mutations = useExplorerMutations(identity, dataSourceId);
  const result = tableQuery.data;

  const dataSourceOptions = (catalog.dataSources.data || []).map((source) => ({
    value: String(source.id),
    label: source.name,
    description: source.kind
  }));
  const schemaOptions = (catalog.schemas.data || [])
    .map((item) => item.schema)
    .filter((value): value is string => Boolean(value))
    .map((value) => ({ value, label: value }));
  const tableOptions = (catalog.tables.data || []).map((item) => ({
    value: item.table,
    label: item.table
  }));

  const chooseSource = (value: string | null) => {
    setDataSourceId(value ? Number(value) : null);
    setSchema(null);
    setTable(null);
    setOffset(0);
  };
  const chooseSchema = (value: string | null) => {
    setSchema(value);
    setTable(null);
    setOffset(0);
  };
  const chooseTable = (value: string | null) => {
    setTable(value);
    setOffset(0);
  };

  const startEdit = (row: Record<string, unknown>) => {
    setSelectedRow(row);
    setEditor('edit');
  };

  const insert = async (values: Record<string, unknown>) => {
    try {
      await mutations.insert.mutateAsync(values);
      setEditor(null);
      notifications.show({ color: 'green', title: 'Row inserted', message: `Added one row to ${table}.` });
    } catch (error) {
      notifyError('Insert failed', error);
    }
  };

  const update = async (values: Record<string, unknown>) => {
    if (!result || !selectedRow) return;
    try {
      await mutations.update.mutateAsync({
        keyValues: keyValues(selectedRow, result.primaryKeys),
        values
      });
      setEditor(null);
      setSelectedRow(null);
      notifications.show({ color: 'green', title: 'Row updated', message: 'The selected row was saved.' });
    } catch (error) {
      notifyError('Update failed', error);
    }
  };

  const remove = async (row: Record<string, unknown>) => {
    if (!result) return;
    const keys = keyValues(row, result.primaryKeys);
    const ok = await confirm({
      title: 'Delete database row',
      message: `Delete the row identified by ${Object.entries(keys).map(([key, value]) => `${key}=${display(value)}`).join(', ')}?\n\nThis changes the connected database and cannot be undone by ForgeTDM.`,
      okText: 'Delete row',
      danger: true
    });
    if (!ok) return;
    try {
      await mutations.remove.mutateAsync(keys);
      notifications.show({ color: 'green', title: 'Row deleted', message: 'Exactly one row was removed.' });
    } catch (error) {
      notifyError('Delete failed', error);
    }
  };

  const runConsoleStatement = async (sql: string, mode: 'read' | 'write') => {
    if (mode === 'read') {
      try {
        setSqlResult(await mutations.runSql.mutateAsync(sql));
      } catch (error) {
        notifyError('Query failed', error);
      }
      return;
    }
    const statement = sql.trim().replace(/\s+/g, ' ');
    const ok = await confirm({
      title: 'Execute SQL change',
      message: `Run this statement against the selected database?\n\n${statement.slice(0, 300)}${statement.length > 300 ? '...' : ''}\n\nThe operation is committed to the connected database and recorded in the audit trail.`,
      okText: 'Execute statement',
      danger: true
    });
    if (!ok) return;
    try {
      const execution = await mutations.executeSql.mutateAsync(sql);
      setSqlResult(execution);
      notifications.show({
        color: 'green',
        title: `${execution.statementType || 'SQL'} completed`,
        message: execution.affectedRows != null && execution.affectedRows >= 0
          ? `${execution.affectedRows} row(s) affected.`
          : 'Statement completed successfully.'
      });
    } catch (error) {
      notifyError('SQL execution failed', error);
    }
  };

  return (
    <main className="data-explorer-page">
      <header className="data-explorer-header">
        <div>
          <Group gap="xs">
            <ThemeIcon variant="light" size="lg"><IconDatabase size={19} /></ThemeIcon>
            <div>
              <Text component="h1" className="data-explorer-title">Data Explorer</Text>
              <Text c="dimmed" size="sm">Browse and safely maintain rows in connected databases.</Text>
            </div>
          </Group>
        </div>
        <Group>
          <Button
            variant="default"
            leftSection={<IconSql size={17} />}
            onClick={() => setSqlOpened(true)}
            disabled={!dataSourceId}
          >
            SQL console
          </Button>
          <Tooltip label={identity ? 'Reload current table' : 'Choose a table first'}>
            <ActionIcon
              size="lg"
              variant="light"
              aria-label="Reload current table"
              disabled={!identity}
              loading={tableQuery.isFetching}
              onClick={() => tableQuery.refetch()}
            >
              <IconRefresh size={18} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </header>

      <Paper className="data-explorer-workspace" withBorder>
        <section className="data-explorer-catalog-bar" aria-label="Table selection">
          <Select
            label="Data source"
            placeholder="Choose a connection"
            data={dataSourceOptions}
            value={dataSourceId ? String(dataSourceId) : null}
            onChange={chooseSource}
            searchable
            clearable
            leftSection={<IconDatabase size={16} />}
          />
          <Select
            label="Schema"
            placeholder={dataSourceId ? 'Choose schema' : 'Choose source first'}
            data={schemaOptions}
            value={schema}
            onChange={chooseSchema}
            searchable
            clearable
            disabled={!dataSourceId}
            rightSection={catalog.schemas.isFetching ? <Loader size={14} /> : undefined}
          />
          <Select
            label="Table"
            placeholder={schema ? 'Choose table' : 'Choose schema first'}
            data={tableOptions}
            value={table}
            onChange={chooseTable}
            searchable
            clearable
            disabled={!schema}
            leftSection={<IconTable size={16} />}
            rightSection={catalog.tables.isFetching ? <Loader size={14} /> : undefined}
          />
          <div className="data-explorer-selection-summary">
            <Text size="xs" fw={800} c="dimmed" tt="uppercase">Selected object</Text>
            <Text fw={700} truncate>{identity ? `${schema}.${table}` : 'No table selected'}</Text>
            <Text size="xs" c="dimmed">{result ? `${result.columns.length} columns · ${result.elapsedMs} ms` : 'Browse a physical table to begin'}</Text>
          </div>
        </section>

        <section className="data-explorer-grid-header">
          <div>
            <Group gap="xs">
              <Text fw={800}>{table || 'Rows'}</Text>
              {result ? <Badge variant="light">{result.rowCount} shown</Badge> : null}
              {result?.primaryKeys.map((key) => <Badge key={key} color="gray" variant="outline">PK {key}</Badge>)}
            </Group>
            <Text size="sm" c="dimmed">
              {result?.editable
                ? 'Edits and deletes are guarded by the physical primary key.'
                : result ? 'No primary key detected. Rows are read-only; inserts remain available.' : 'Select a table to load data.'}
            </Text>
          </div>
          <Button
            leftSection={<IconPlus size={17} />}
            onClick={() => { setSelectedRow(null); setEditor('insert'); }}
            disabled={!result || !canWrite}
          >
            Add row
          </Button>
        </section>

        <div className="data-explorer-grid">
          {tableQuery.isPending && identity ? (
            <div className="data-explorer-empty"><Loader size="sm" /><Text c="dimmed">Loading table rows</Text></div>
          ) : tableQuery.isError ? (
            <Alert color="red" icon={<IconAlertTriangle size={18} />} title="Table could not be loaded">
              {errorMessage(tableQuery.error)}
            </Alert>
          ) : result ? (
            <ExplorerGrid
              columns={result.columns}
              rows={result.rows}
              editable={result.editable && canWrite}
              onEdit={startEdit}
              onDelete={remove}
            />
          ) : (
            <div className="data-explorer-empty">
              <ThemeIcon variant="light" color="gray" size={48}><IconTable size={24} /></ThemeIcon>
              <Text fw={750}>Choose a table</Text>
              <Text c="dimmed" size="sm">Rows, column metadata, and safe CRUD controls will appear here.</Text>
            </div>
          )}
        </div>

        <footer className="data-explorer-footer">
          <Text size="sm" c="dimmed">
            {result ? `Rows ${result.offset + 1}-${result.offset + result.rowCount}${result.hasMore ? '+' : ''}` : 'No data loaded'}
          </Text>
          <Group gap="xs">
            <Button
              variant="default"
              size="xs"
              leftSection={<IconChevronLeft size={15} />}
              disabled={!result || offset === 0}
              onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
            >
              Previous
            </Button>
            <Button
              variant="default"
              size="xs"
              rightSection={<IconChevronRight size={15} />}
              disabled={!result?.hasMore}
              onClick={() => setOffset(offset + PAGE_SIZE)}
            >
              Next
            </Button>
          </Group>
        </footer>
      </Paper>

      <RowEditorDrawer
        key={`${editor || 'closed'}-${selectedRow && result ? result.primaryKeys.map((key) => display(cellValue(selectedRow, key))).join('|') : 'new'}`}
        opened={Boolean(editor)}
        mode={editor || 'insert'}
        columns={result?.columns || []}
        primaryKeys={result?.primaryKeys || []}
        row={selectedRow}
        submitting={mutations.insert.isPending || mutations.update.isPending}
        onClose={() => { setEditor(null); setSelectedRow(null); }}
        onSubmit={editor === 'edit' ? update : insert}
      />
      <QueryConsoleDrawer
        key={`${sqlOpened ? 'open' : 'closed'}-${dataSourceId || ''}-${schema || ''}-${table || ''}`}
        opened={sqlOpened}
        tableName={table}
        schema={schema}
        result={sqlResult}
        running={mutations.runSql.isPending || mutations.executeSql.isPending}
        canWrite={canWrite}
        onClose={() => setSqlOpened(false)}
        onRun={runConsoleStatement}
      />
      {confirmElement}
    </main>
  );
}

function ExplorerGrid({
  columns,
  rows,
  editable,
  onEdit,
  onDelete
}: {
  columns: ExplorerColumn[];
  rows: Array<Record<string, unknown>>;
  editable: boolean;
  onEdit: (row: Record<string, unknown>) => void;
  onDelete: (row: Record<string, unknown>) => void;
}) {
  const minWidth = useMemo(() => Math.max(760, columns.length * 180 + 92), [columns.length]);
  if (!rows.length) {
    return <div className="data-explorer-empty"><Text fw={700}>This table has no rows</Text><Text c="dimmed" size="sm">Use Add row to create the first record.</Text></div>;
  }
  return (
    <Table.ScrollContainer minWidth={minWidth} className="data-explorer-scroll">
      <Table striped highlightOnHover stickyHeader>
        <Table.Thead>
          <Table.Tr>
            {columns.map((column) => (
              <Table.Th key={column.column}>
                <Text size="xs" fw={800}>{column.column}</Text>
                <Text size="xs" c="dimmed" fw={500}>{column.type || 'unknown'}</Text>
              </Table.Th>
            ))}
            <Table.Th className="data-explorer-actions-column">Actions</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row, rowIndex) => (
            <Table.Tr key={rowIndex}>
              {columns.map((column) => {
                const value = cellValue(row, column.column);
                return (
                  <Table.Td key={column.column}>
                    <Tooltip label={display(value)} disabled={display(value).length < 40} multiline maw={420}>
                      <Code className={value == null ? 'data-explorer-null' : 'data-explorer-cell'}>{display(value)}</Code>
                    </Tooltip>
                  </Table.Td>
                );
              })}
              <Table.Td className="data-explorer-actions-column">
                <Group gap={4} wrap="nowrap">
                  <Tooltip label={editable ? 'Edit row' : 'A primary key and write permission are required'}>
                    <ActionIcon variant="subtle" disabled={!editable} aria-label="Edit row" onClick={() => onEdit(row)}>
                      <IconEdit size={17} />
                    </ActionIcon>
                  </Tooltip>
                  <Tooltip label={editable ? 'Delete row' : 'A primary key and write permission are required'}>
                    <ActionIcon variant="subtle" color="red" disabled={!editable} aria-label="Delete row" onClick={() => onDelete(row)}>
                      <IconTrash size={17} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

function cellValue(row: Record<string, unknown>, column: string) {
  const key = Object.keys(row).find((candidate) => candidate.toLowerCase() === column.toLowerCase());
  return key ? row[key] : null;
}

function keyValues(row: Record<string, unknown>, keys: string[]) {
  return Object.fromEntries(keys.map((key) => [key, cellValue(row, key)]));
}

function display(value: unknown) {
  if (value == null) return 'NULL';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Unexpected server error';
}

function notifyError(title: string, error: unknown) {
  notifications.show({ color: 'red', title, message: errorMessage(error) });
}
