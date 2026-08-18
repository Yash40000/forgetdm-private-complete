'use client';

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Checkbox,
  Drawer,
  Group,
  Loader,
  Modal,
  ScrollArea,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Tooltip
} from '@mantine/core';
import { IconDatabase, IconSearch, IconTable } from '@tabler/icons-react';

import { useDataSources, useSchemas, useTables } from '@/features/pii-discovery/hooks';
import type { DataSource } from '@/lib/types';
import type { ApplicationSlice } from './types';

type Props = {
  opened: boolean;
  editing: ApplicationSlice | null;
  existing: ApplicationSlice[];
  onClose: () => void;
  onSave: (slice: ApplicationSlice) => void;
};

type BrowserMode = 'source' | 'schema' | 'tables' | null;

export function ApplicationDrawer({ opened, editing, existing, onClose, onSave }: Props) {
  const sources = useDataSources();
  const [label, setLabel] = useState('');
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [schema, setSchema] = useState<string | null>(null);
  const [tables, setTables] = useState<string[]>([]);
  const [browseMode, setBrowseMode] = useState<BrowserMode>(null);
  const [browseSearch, setBrowseSearch] = useState('');
  const [tableDraft, setTableDraft] = useState<string[]>([]);
  const numericSourceId = sourceId ? Number(sourceId) : null;
  const schemas = useSchemas(numericSourceId);
  const catalog = useTables(numericSourceId, schema);

  useEffect(() => {
    if (!opened) return;
    setLabel(editing?.label || '');
    setSourceId(editing ? String(editing.dataSourceId) : null);
    setSchema(editing?.schema || null);
    setTables(editing?.tables || []);
    setBrowseMode(null);
    setBrowseSearch('');
  }, [editing, opened]);

  const sourceCandidates = useMemo(
    () => (sources.data || []).filter((source) => ['SOURCE', 'BOTH'].includes(String(source.role || '').toUpperCase())),
    [sources.data]
  );
  const schemaOptions = useMemo(
    () => unique((schemas.data || []).map((row) => catalogValue(row, 'schema')).filter(Boolean)).sort((a, b) => a.localeCompare(b)),
    [schemas.data]
  );
  const tableOptions = useMemo(
    () => unique((catalog.data || []).map((row) => catalogValue(row, 'table')).filter(Boolean)).sort((a, b) => a.localeCompare(b)),
    [catalog.data]
  );
  const selectedSource = sourceCandidates.find((source) => String(source.id) === sourceId) || null;
  const filteredSources = useMemo(
    () => sourceCandidates.filter((source) => sourceSearchText(source).includes(normalize(browseSearch))),
    [browseSearch, sourceCandidates]
  );
  const filteredSchemas = useMemo(
    () => schemaOptions.filter((value) => normalize(value).includes(normalize(browseSearch))),
    [browseSearch, schemaOptions]
  );
  const filteredTables = useMemo(
    () => tableOptions.filter((value) => normalize(value).includes(normalize(browseSearch))),
    [browseSearch, tableOptions]
  );

  const duplicate = existing.some((slice) =>
    slice.id !== editing?.id && slice.dataSourceId === numericSourceId && slice.schema === schema
  );
  const canSave = Boolean(label.trim().length >= 3 && numericSourceId && schema && tables.length > 0 && !duplicate);

  const submit = () => {
    if (!canSave || !numericSourceId || !schema) return;
    const source = (sources.data || []).find((item) => item.id === numericSourceId);
    onSave({
      id: editing?.id || crypto.randomUUID(),
      label: label.trim(),
      dataSourceId: numericSourceId,
      dataSourceName: source?.name || `Source ${numericSourceId}`,
      schema,
      tables
    });
  };

  const openBrowser = (mode: Exclude<BrowserMode, null>) => {
    if (mode === 'tables') setTableDraft(tables);
    setBrowseSearch('');
    setBrowseMode(mode);
  };

  const chooseSource = (source: DataSource) => {
    setSourceId(String(source.id));
    setSchema(null);
    setTables([]);
    setBrowseMode(null);
  };

  const chooseSchema = (value: string) => {
    setSchema(value);
    setTables([]);
    setBrowseMode(null);
  };

  const toggleTable = (table: string) => {
    setTableDraft((current) => current.includes(table)
      ? current.filter((value) => value !== table)
      : [...current, table]);
  };

  return (
    <>
      <Drawer opened={opened} onClose={onClose} position="right" size="lg" title={editing ? 'Edit source application' : 'Add source application'}>
        <Stack gap="lg">
          <TextInput
            label="Application name"
            description="A clear business label, such as Core Banking or Card Processing."
            placeholder="Core Banking"
            value={label}
            onChange={(event) => setLabel(event.currentTarget.value)}
            spellCheck={false}
            maxLength={80}
          />
          <CatalogField
            label="Data source"
            value={selectedSource?.name || ''}
            placeholder="Choose a connected source"
            icon={<IconDatabase size={17} />}
            onBrowse={() => openBrowser('source')}
          />
          <CatalogField
            label="Schema"
            value={schema || ''}
            placeholder={sourceId ? 'Choose a schema' : 'Choose a data source first'}
            disabled={!sourceId}
            onBrowse={() => openBrowser('schema')}
          />
          <section className="entity-architecture-table-selection">
            <Group justify="space-between" align="flex-start" wrap="nowrap">
              <div>
                <Text fw={700} size="sm">Tables in this application</Text>
                <Text c="dimmed" size="xs">Only selected tables appear on the architecture canvas.</Text>
              </div>
              <Button
                size="xs"
                variant="light"
                leftSection={<IconSearch size={15} />}
                disabled={!schema}
                onClick={() => openBrowser('tables')}
              >
                Browse tables
              </Button>
            </Group>
            <Group gap="xs" mt="sm">
              <Badge variant="light" color={tables.length ? 'blue' : 'gray'}>{tables.length} selected</Badge>
              {tables.slice(0, 4).map((table) => <Badge key={table} variant="outline" color="gray">{table}</Badge>)}
              {tables.length > 4 ? <Text size="xs" c="dimmed">+{tables.length - 4} more</Text> : null}
            </Group>
          </section>
          {duplicate ? <Text c="red" size="sm">This data source and schema are already on the canvas.</Text> : null}
          <Group justify="flex-end" mt="sm">
            <Button variant="subtle" color="gray" onClick={onClose}>Cancel</Button>
            <Button onClick={submit} disabled={!canSave}>{editing ? 'Save changes' : 'Add to canvas'}</Button>
          </Group>
        </Stack>
      </Drawer>

      <Modal
        opened={Boolean(browseMode)}
        onClose={() => setBrowseMode(null)}
        title={browseMode === 'source' ? 'Choose a data source' : browseMode === 'schema' ? 'Choose a schema' : 'Choose tables'}
        size={browseMode === 'tables' ? '100%' : 'lg'}
        fullScreen={browseMode === 'tables'}
        centered
      >
        <Stack gap="md" className={browseMode === 'tables' ? 'entity-architecture-table-browser' : undefined}>
          <TextInput
            leftSection={<IconSearch size={16} />}
            placeholder={browseMode === 'source' ? 'Search name, engine, role, or environment' : browseMode === 'schema' ? 'Search schemas' : 'Search tables'}
            value={browseSearch}
            onChange={(event) => setBrowseSearch(event.currentTarget.value)}
            spellCheck={false}
            autoFocus
          />

          {browseMode === 'source' ? (
            sources.isPending ? <BrowserLoading label="Loading data sources" /> : sources.isError ? (
              <Alert color="red">{sources.error instanceof Error ? sources.error.message : 'Could not load data sources.'}</Alert>
            ) : (
              <div className="entity-architecture-browser-list">
                {filteredSources.map((source) => (
                  <button key={source.id} type="button" className="entity-architecture-browser-row" onClick={() => chooseSource(source)}>
                    <span className="entity-architecture-browser-icon"><IconDatabase size={18} /></span>
                    <span className="entity-architecture-browser-copy">
                      <b>{source.name}</b>
                      <small>{source.kind || 'database'} / {source.role || 'BOTH'} / {source.environment || 'environment not set'}</small>
                    </span>
                    {String(source.id) === sourceId ? <Badge variant="light">Selected</Badge> : null}
                  </button>
                ))}
                {!filteredSources.length ? <BrowserEmpty label="No matching data sources." /> : null}
              </div>
            )
          ) : null}

          {browseMode === 'schema' ? (
            schemas.isPending ? <BrowserLoading label="Loading schemas" /> : schemas.isError ? (
              <Alert color="red">{schemas.error instanceof Error ? schemas.error.message : 'Could not load schemas.'}</Alert>
            ) : (
              <div className="entity-architecture-browser-list">
                {filteredSchemas.map((value) => (
                  <button key={value} type="button" className="entity-architecture-browser-row" onClick={() => chooseSchema(value)}>
                    <span className="entity-architecture-browser-icon"><IconDatabase size={18} /></span>
                    <span className="entity-architecture-browser-copy"><b>{value}</b><small>Schema</small></span>
                    {value === schema ? <Badge variant="light">Selected</Badge> : null}
                  </button>
                ))}
                {!filteredSchemas.length ? <BrowserEmpty label="No matching schemas." /> : null}
              </div>
            )
          ) : null}

          {browseMode === 'tables' ? (
            <>
              <Group justify="space-between" className="entity-architecture-table-browser-toolbar">
                <Group gap="xs">
                  <Badge variant="light">{tableDraft.length} selected</Badge>
                  <Text size="sm" c="dimmed">{selectedSource?.name} / {schema}</Text>
                </Group>
                <Group gap="xs">
                  <Button variant="default" size="xs" disabled={!tableOptions.length} onClick={() => setTableDraft(tableOptions)}>
                    Select all ({tableOptions.length})
                  </Button>
                  <Button variant="subtle" color="gray" size="xs" disabled={!tableDraft.length} onClick={() => setTableDraft([])}>Clear</Button>
                </Group>
              </Group>
              {catalog.isPending ? <BrowserLoading label="Loading tables" /> : catalog.isError ? (
                <Alert color="red">{catalog.error instanceof Error ? catalog.error.message : 'Could not load tables.'}</Alert>
              ) : (
                <ScrollArea className="entity-architecture-table-browser-scroll" type="auto">
                  <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="xs">
                    {filteredTables.map((table) => {
                      const selected = tableDraft.includes(table);
                      return (
                        <button
                          key={table}
                          type="button"
                          className={`entity-architecture-table-choice ${selected ? 'is-selected' : ''}`}
                          onClick={() => toggleTable(table)}
                        >
                          <IconTable size={17} />
                          <span>{table}</span>
                          <Checkbox checked={selected} onChange={() => undefined} tabIndex={-1} aria-hidden />
                        </button>
                      );
                    })}
                  </SimpleGrid>
                  {!filteredTables.length ? <BrowserEmpty label="No matching tables." /> : null}
                </ScrollArea>
              )}
              <Group justify="flex-end" className="entity-architecture-table-browser-footer">
                <Button variant="default" onClick={() => setBrowseMode(null)}>Cancel</Button>
                <Button
                  disabled={!tableDraft.length}
                  onClick={() => {
                    setTables(tableDraft);
                    setBrowseMode(null);
                  }}
                >
                  Add {tableDraft.length || ''} table{tableDraft.length === 1 ? '' : 's'}
                </Button>
              </Group>
            </>
          ) : null}
        </Stack>
      </Modal>
    </>
  );
}

function CatalogField({
  label,
  value,
  placeholder,
  disabled,
  icon,
  onBrowse
}: {
  label: string;
  value: string;
  placeholder: string;
  disabled?: boolean;
  icon?: ReactNode;
  onBrowse: () => void;
}) {
  return (
    <div>
      <Text fw={700} size="sm" mb={5}>{label}</Text>
      <Group gap="xs" wrap="nowrap">
        <TextInput
          value={value}
          placeholder={placeholder}
          readOnly
          disabled={disabled}
          leftSection={icon}
          style={{ flex: 1 }}
          onClick={() => !disabled && onBrowse()}
        />
        <Tooltip label={`Browse ${label.toLowerCase()}`}>
          <ActionIcon variant="light" size={36} disabled={disabled} aria-label={`Browse ${label.toLowerCase()}`} onClick={onBrowse}>
            <IconSearch size={17} />
          </ActionIcon>
        </Tooltip>
      </Group>
    </div>
  );
}

function BrowserLoading({ label }: { label: string }) {
  return <Group justify="center" py="xl"><Loader size="sm" /><Text c="dimmed">{label}...</Text></Group>;
}

function BrowserEmpty({ label }: { label: string }) {
  return <Text c="dimmed" size="sm" ta="center" py="xl">{label}</Text>;
}

function catalogValue(row: Record<string, unknown>, key: string) {
  const value = row[key] ?? row.name ?? row.label;
  return typeof value === 'string' ? value : value == null ? '' : String(value);
}

function sourceSearchText(source: DataSource) {
  return normalize([source.name, source.kind, source.role, source.environment, source.tags].filter(Boolean).join(' '));
}

function normalize(value?: string | null) {
  return String(value || '').trim().toLowerCase();
}

function unique(values: string[]) {
  return [...new Set(values)];
}
