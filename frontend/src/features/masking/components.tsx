'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { ActionIcon, Autocomplete, Badge, Button, Collapse, Divider, Group, Modal, Paper, SegmentedControl, Select, SimpleGrid, Stack, Switch, Text, TextInput, ThemeIcon, Tooltip } from '@mantine/core';
import { IconArrowRight, IconPlus, IconSearch, IconShieldCheck, IconSparkles, IconTrash } from '@tabler/icons-react';

import type { MaskingScript } from '@/lib/types';
import { displayParam, functionCategory, functionSummary, maskParamLabel, normalizeParam, optionDataForParam, safeInputValue, technicalInputProps } from './utils';

export function MaskingHeader({
  title,
  eyebrow,
  description,
  action
}: {
  title: string;
  eyebrow: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="masking-page-head">
      <div>
        <Text size="xs" tt="uppercase" fw={850} c="dimmed">
          {eyebrow}
        </Text>
        <h1>{title}</h1>
        <Text c="dimmed" maw={820}>
          {description}
        </Text>
      </div>
      {action}
    </div>
  );
}

export function MaskingMetric({ label, value, detail }: { label: string; value: string | number; detail: string }) {
  return (
    <Paper className="masking-metric" p="md">
      <Text size="xs" tt="uppercase" fw={850} c="dimmed">
        {label}
      </Text>
      <Text className="masking-metric-value">{value}</Text>
      <Text size="sm" c="dimmed">
        {detail}
      </Text>
    </Paper>
  );
}

export function ParamControl({
  functionName,
  index,
  value,
  onChange,
  scripts,
  valueLists,
  lookupReferences,
  size = 'xs'
}: {
  functionName: string;
  index: 1 | 2;
  value: string;
  onChange: (value: string) => void;
  scripts?: MaskingScript[];
  valueLists?: Array<{ name: string }>;
  lookupReferences?: string[];
  size?: 'xs' | 'sm';
}) {
  const label = maskParamLabel(functionName, index);
  if (!label) {
    return (
      <TextInput
        size={size}
        label={`Param ${index}`}
        value=""
        disabled
        placeholder="Not used"
        {...technicalInputProps}
      />
    );
  }

  if (label.includes('@value-list')) {
    const references = [...new Set([...(valueLists || []).map((list) => `@${list.name}`), ...(lookupReferences || [])])];
    return (
      <Autocomplete
        size={size}
        label={shortLabel(label)}
        value={value}
        data={references}
        placeholder="Type inline values or choose @list"
        onChange={onChange}
        {...technicalInputProps}
      />
    );
  }

  const options = optionDataForParam(label, scripts || []);
  if (options.length) {
    const current = displayParam(value);
    const hasCurrent = !current || options.some((option) => option.value === current);
    const data = hasCurrent ? options : [{ value: current, label: `${current} (custom)` }, ...options];
    return (
      <Select
        size={size}
        label={shortLabel(label)}
        data={data}
        value={current || null}
        placeholder="Optional"
        searchable
        clearable
        onChange={(next) => onChange(normalizeParam(next) || '')}
      />
    );
  }

  return (
    <TextInput
      size={size}
      label={shortLabel(label)}
      value={value}
      placeholder="Optional"
      onChange={(event) => onChange(safeInputValue(event))}
      {...technicalInputProps}
    />
  );
}

/** Detect the lookup functions whose param2 is a free-form options string. */
export function isLookupOptionsFunction(functionName: string) {
  return functionName === 'HASH_LOOKUP' || functionName === 'DIRECT_LOOKUP';
}

function parseLookupOptions(value?: string): Record<string, string> {
  const map: Record<string, string> = {};
  for (const part of (value || '').split(';')) {
    const token = part.trim();
    if (!token) continue;
    const eq = token.indexOf('=');
    if (eq < 0) map[token.toUpperCase()] = '';
    else map[token.slice(0, eq).trim().toUpperCase()] = token.slice(eq + 1).trim();
  }
  return map;
}

function serializeLookupOptions(map: Record<string, string>): string {
  return Object.entries(map)
    .filter(([key]) => key)
    .map(([key, val]) => (val === '' ? key : `${key}=${val}`))
    .join(';');
}

const LOOKUP_ACTION_DATA = [
  { value: '', label: 'Error (fail closed)' },
  { value: 'PRESERVE', label: 'Keep source value' },
  { value: 'NULL', label: 'Null out' },
  { value: 'REDACT', label: 'Redact' },
  { value: 'DEFAULT', label: 'Default value' }
];

function stripLookupPrefix(param1: string) {
  return param1.replace(/^@lookup:(hash|direct):/i, '').replace(/^@/, '');
}

function splitCsv(value: string | null | undefined) {
  return (value || '').split(',').map((part) => part.trim()).filter(Boolean);
}

function joinCsv(values: string[]) {
  return values.map((value) => value.trim()).filter(Boolean).join(', ');
}

const COMMON_SOURCE_COLUMNS = [
  'customer_id',
  'customer_no',
  'party_id',
  'account_id',
  'first_name',
  'last_name',
  'full_name',
  'email',
  'phone',
  'ssn',
  'date_of_birth',
  'address_line1',
  'city',
  'state',
  'zip'
];

const COMMON_LOOKUP_VALUE_COLUMNS = [
  'value',
  'replacement_value',
  'first_name',
  'last_name',
  'full_name',
  'address_line1',
  'city',
  'state',
  'zip',
  'email',
  'phone',
  'account_type',
  'status'
];

/**
 * Single form for a lookup rule — the governed lookup table (or inline rows), the source column(s)
 * that are hashed/matched, the destination column(s) and value column, plus trim/case/cache/reserved
 * options. Table + rows live in param1; everything else compiles to the param2 options string. A raw
 * override stays available under Advanced so nothing is lost.
 */
export function LookupOptionsBuilder({
  functionName,
  param1,
  param2,
  onParam1Change,
  onParam2Change,
  lookupReferences
}: {
  functionName: string;
  param1: string;
  param2: string;
  onParam1Change: (value: string) => void;
  onParam2Change: (value: string) => void;
  lookupReferences?: string[];
}) {
  const isHash = functionName === 'HASH_LOOKUP';
  const mode = isHash ? 'hash' : 'direct';
  const governed = param1.trim().toLowerCase().startsWith('@lookup:');
  const [sourceMode, setSourceMode] = useState<'GOVERNED' | 'INLINE'>(governed || !param1.trim() ? 'GOVERNED' : 'INLINE');
  const [advanced, setAdvanced] = useState(false);
  const [browserOpen, setBrowserOpen] = useState(false);
  const [browserTarget, setBrowserTarget] = useState<{ kind: 'table' | 'source' | 'lookup'; index: number | 'new' } | null>(null);
  const [sourceRowCount, setSourceRowCount] = useState(1);
  const map = parseLookupOptions(param2);
  const setOpt = (key: string, val: string) => {
    const next = { ...map };
    if (!val) delete next[key];
    else next[key] = val;
    onParam2Change(serializeLookupOptions(next));
  };
  const cacheOn = map.CACHE !== 'OFF' && !('NOCACHE' in map);
  const setCache = (on: boolean) => {
    const next = { ...map };
    delete next.NOCACHE;
    if (on) delete next.CACHE;
    else next.CACHE = 'OFF';
    onParam2Change(serializeLookupOptions(next));
  };
  const caseValue = map.CASE && map.CASE.toUpperCase() !== 'SENSITIVE' ? map.CASE.toUpperCase() : '';
  const usesDefault = ['NOT_FOUND', 'NULL', 'SPACES', 'ZERO_LEN'].some((key) => map[key] === 'DEFAULT');
  const tableName = stripLookupPrefix(param1);
  const sourceColumns = splitCsv(map.SOURCE);
  const sourceRows = Array.from(
    { length: Math.max(sourceRowCount, sourceColumns.length || 1) },
    (_, index) => sourceColumns[index] || ''
  );
  const lookupValueNames = splitCsv(map.VALUE_NAMES);
  const columnChoices = useMemo(
    () => Array.from(new Set([...sourceRows, ...COMMON_SOURCE_COLUMNS].filter(Boolean))).sort(),
    [sourceRows]
  );
  const lookupColumnChoices = useMemo(
    () => Array.from(new Set([...lookupValueNames, ...COMMON_LOOKUP_VALUE_COLUMNS].filter(Boolean))).sort(),
    [lookupValueNames]
  );
  const tableOptions = (lookupReferences || [])
    .filter((ref) => ref.toLowerCase().startsWith(`@lookup:${mode}:`))
    .map((ref) => stripLookupPrefix(ref));
  const tableChoices = useMemo(
    () => Array.from(new Set(tableOptions)).sort(),
    [tableOptions]
  );
  const setSourceColumn = (index: number, value: string) => {
    const next = [...sourceRows];
    next[index] = value;
    setOpt('SOURCE', joinCsv(next));
  };
  const addSourceColumn = (value = '') => {
    setSourceRowCount((count) => Math.max(count + 1, sourceRows.length + 1));
    if (value) setOpt('SOURCE', joinCsv([...sourceRows.filter(Boolean), value]));
  };
  const removeSourceColumn = (index: number) => {
    setSourceRowCount((count) => Math.max(1, count - 1));
    setOpt('SOURCE', joinCsv(sourceRows.filter((_, rowIndex) => rowIndex !== index)));
  };
  const chooseBrowserColumn = (value: string) => {
    if (!browserTarget) return;
    if (browserTarget.kind === 'table') {
      onParam1Change(value ? `@lookup:${mode}:${value.trim()}` : '');
    } else if (browserTarget.kind === 'source') {
      if (browserTarget.index === 'new') addSourceColumn(value);
      else setSourceColumn(browserTarget.index, value);
    } else if (typeof browserTarget.index === 'number') {
      setLookupValueName(browserTarget.index, value);
    }
    setBrowserOpen(false);
    setBrowserTarget(null);
  };
  const setLookupValueName = (index: number, value: string) => {
    const next = [...lookupValueNames];
    next[index] = value;
    setOpt('VALUE_NAMES', joinCsv(next));
  };
  const trimMode = map.TRIM || '';
  const setTrimMode = (modeValue: string, checked: boolean) => setOpt('TRIM', checked ? modeValue : '');

  return (
    <div className="masking-lookup-builder">
      <Group justify="space-between" align="flex-start" gap="xs">
        <div>
          <Text size="xs" fw={850} tt="uppercase" c="dimmed">
            {isHash ? 'Optim-style hash lookup' : 'Direct lookup'}
          </Text>
          <Text size="xs" c="dimmed">
            {isHash
              ? 'Hash one or more source columns to a governed lookup row; this rule may write a different target column.'
              : 'Match source columns to governed replacement values and fail closed when a key is missing.'}
          </Text>
        </div>
        <Badge variant="light">{isHash ? 'SOURCE HASH' : 'EXACT MATCH'}</Badge>
      </Group>

      <SegmentedControl
        size="xs"
        mt="sm"
        value={sourceMode}
        onChange={(next) => setSourceMode(next as 'GOVERNED' | 'INLINE')}
        data={[
          { label: 'Governed table', value: 'GOVERNED' },
          { label: 'Inline rows', value: 'INLINE' }
        ]}
      />

      {sourceMode === 'GOVERNED' ? (
        <SimpleGrid cols={{ base: 1, sm: isHash ? 2 : 1 }} spacing="xs" mt="xs">
          <div className="masking-lookup-field-with-browser">
            <Autocomplete
              size="xs"
              label="Lookup table name"
              description="Type a governed lookup table or browse"
              placeholder="demo.us-first-names"
              data={tableChoices}
              value={tableName}
              onChange={(next) => onParam1Change(next ? `@lookup:${mode}:${next.trim()}` : '')}
              {...technicalInputProps}
            />
            <Tooltip label="Browse lookup tables">
              <ActionIcon
                size="lg"
                variant="light"
                aria-label="Browse lookup tables"
                onClick={() => {
                  setBrowserTarget({ kind: 'table', index: 'new' });
                  setBrowserOpen(true);
                }}
              >
                <IconSearch size={16} />
              </ActionIcon>
            </Tooltip>
          </div>
          {isHash ? (
            <TextInput
              size="xs"
              label="Hash seed"
              description="Same seed + same source value picks the same lookup row"
              placeholder="0"
              value={map.SEED || ''}
              onChange={(event) => setOpt('SEED', safeInputValue(event))}
              {...technicalInputProps}
            />
          ) : null}
        </SimpleGrid>
      ) : (
        <TextInput
          size="xs"
          mt="xs"
          label={isHash ? 'Lookup rows' : 'Mappings'}
          description="key=>value pairs separated by |"
          placeholder={isHash ? '1=>Ava|2=>Liam|3=>Noah' : 'CHK=>EVERYDAY|SAV=>RESERVE'}
          value={param1}
          onChange={(event) => onParam1Change(safeInputValue(event))}
          {...technicalInputProps}
        />
      )}

      <Paper className="masking-lookup-section" p="sm" mt="sm">
        <Group justify="space-between" mb="xs" align="flex-start">
          <div>
            <Text size="sm" fw={800}>Source and lookup mapping</Text>
            <Text size="xs" c="dimmed">
              The source column drives the hash. Blank uses the current column being masked; add more source columns for composite Optim-style keys.
            </Text>
          </div>
          <Button
            size="compact-xs"
            variant="light"
            leftSection={<IconPlus size={13} />}
            onClick={() => addSourceColumn()}
          >
            Add source
          </Button>
        </Group>
        <Stack gap={6}>
          {sourceRows.map((column, index) => (
            <Group key={`source-${index}`} gap="xs" wrap="nowrap" align="end" className={`masking-source-map-row ${isHash ? 'is-hash' : 'is-direct'}`}>
              <Autocomplete
                size="xs"
                label={index === 0 ? 'Source column used for hash' : undefined}
                placeholder={index === 0 ? 'blank = current masked column' : 'customer_no'}
                value={column}
                data={columnChoices}
                onChange={(value) => setSourceColumn(index, value)}
                {...technicalInputProps}
              />
              <Tooltip label="Browse source columns">
                <ActionIcon
                  size="lg"
                  variant="light"
                  aria-label="Browse source columns"
                  onClick={() => {
                    setBrowserTarget({ kind: 'source', index });
                    setBrowserOpen(true);
                  }}
                >
                  <IconSearch size={16} />
                </ActionIcon>
              </Tooltip>
              <Text className="masking-map-arrow">{isHash ? 'hashes to' : 'maps to'}</Text>
              <TextInput
                size="xs"
                label={index === 0 ? (isHash ? 'Lookup value column' : 'Lookup replacement') : undefined}
                placeholder={isHash ? `first_name` : 'replacement'}
                value={isHash ? (lookupValueNames[index] || '') : ''}
                disabled={!isHash}
                onChange={(event) => setLookupValueName(index, safeInputValue(event))}
                {...technicalInputProps}
              />
              {isHash ? (
                <Tooltip label="Browse lookup value columns">
                  <ActionIcon
                    size="lg"
                    variant="light"
                    aria-label="Browse lookup value columns"
                    onClick={() => {
                      setBrowserTarget({ kind: 'lookup', index });
                      setBrowserOpen(true);
                    }}
                  >
                    <IconSearch size={16} />
                  </ActionIcon>
                </Tooltip>
              ) : null}
              {isHash ? (
                <Text className="masking-value-index" title={`Lookup row value ${index + 1}`}>
                  #{index + 1}
                </Text>
              ) : null}
              <Tooltip label="Remove source mapping">
                <ActionIcon
                  size="lg"
                  variant="subtle"
                  color="red"
                  aria-label="Remove source mapping"
                  disabled={sourceRows.length === 1 && !column}
                  onClick={() => removeSourceColumn(index)}
                >
                  <IconTrash size={15} />
                </ActionIcon>
              </Tooltip>
            </Group>
          ))}
        </Stack>
      </Paper>

      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs" mt="sm">
        <TextInput
          size="xs"
          label="Destination column(s)"
          description="Target column(s) this rule feeds"
          placeholder="first_name, last_name"
          value={map.DEST || ''}
          onChange={(event) => setOpt('DEST', safeInputValue(event))}
          {...technicalInputProps}
        />
        {isHash ? (
          <TextInput
            size="xs"
            label="Value column #"
            description="Which lookup-row column this field takes"
            placeholder="1"
            value={map.VALUE || ''}
            onChange={(event) => setOpt('VALUE', safeInputValue(event))}
            {...technicalInputProps}
          />
        ) : null}
        {isHash ? (
          <TextInput
            size="xs"
            label="Column separator"
            placeholder="~"
            value={map.VCOLSEP || ''}
            onChange={(event) => setOpt('VCOLSEP', safeInputValue(event))}
            {...technicalInputProps}
          />
        ) : null}
      </SimpleGrid>

      <Text size="xs" fw={800} c="dimmed" mt="sm" mb={4}>
        Matching options
      </Text>
      <Paper className="masking-lookup-section" p="sm">
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
          <Switch
            size="xs"
            label="Uppercase before lookup"
            checked={caseValue === 'UPPER'}
            onChange={(event) => setOpt('CASE', event.currentTarget.checked ? 'UPPER' : '')}
          />
          <Switch
            size="xs"
            label="Trim leading and trailing spaces"
            checked={trimMode === 'BOTH' || trimMode === 'TRUE'}
            onChange={(event) => setTrimMode('BOTH', event.currentTarget.checked)}
          />
          <Switch
            size="xs"
            label="Trim trailing blanks only"
            checked={trimMode === 'RIGHT'}
            onChange={(event) => setTrimMode('RIGHT', event.currentTarget.checked)}
          />
          <Switch size="xs" label="Cache lookup values" checked={cacheOn} onChange={(event) => setCache(event.currentTarget.checked)} />
        </SimpleGrid>
        <Divider my="sm" />
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
          {isHash ? null : (
            <Select
              size="xs"
              label="If no match"
              data={LOOKUP_ACTION_DATA}
              value={map.NOT_FOUND || ''}
              onChange={(next) => setOpt('NOT_FOUND', next || '')}
            />
          )}
          <TextInput
            size="xs"
            label="Trim characters"
            placeholder="e.g. , -"
            value={map.TRIM_CHARS || ''}
            onChange={(event) => setOpt('TRIM_CHARS', safeInputValue(event))}
            {...technicalInputProps}
          />
          {isHash ? (
            <Select
              size="xs"
              label="If hash row is not found"
              data={LOOKUP_ACTION_DATA}
              value={map.NOT_FOUND || ''}
              onChange={(next) => setOpt('NOT_FOUND', next || '')}
            />
          ) : null}
        </SimpleGrid>
      </Paper>

      <Group justify="space-between" mt="sm">
        <Button size="compact-xs" variant="subtle" onClick={() => setAdvanced((open) => !open)}>
          {advanced ? 'Hide advanced' : 'Advanced'}
        </Button>
      </Group>

      <Collapse in={advanced}>
        <Stack gap="xs" mt="xs">
          {isHash ? (
            <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
              <Select size="xs" label="NULL source" data={LOOKUP_ACTION_DATA} value={map.NULL || ''} onChange={(next) => setOpt('NULL', next || '')} />
              <Select size="xs" label="Spaces source" data={LOOKUP_ACTION_DATA} value={map.SPACES || ''} onChange={(next) => setOpt('SPACES', next || '')} />
              <Select size="xs" label="Zero-length" data={LOOKUP_ACTION_DATA} value={map.ZERO_LEN || ''} onChange={(next) => setOpt('ZERO_LEN', next || '')} />
            </SimpleGrid>
          ) : null}
          {usesDefault ? (
            <TextInput
              size="xs"
              label="Default value"
              description="Inserted for any option set to Default value"
              value={map.DEFAULT || ''}
              onChange={(event) => setOpt('DEFAULT', safeInputValue(event))}
              {...technicalInputProps}
            />
          ) : null}
          <TextInput
            size="xs"
            label="Raw options"
            description="Everything above compiles to this - edit directly if you prefer"
            value={param2}
            onChange={(event) => onParam2Change(safeInputValue(event))}
            {...technicalInputProps}
          />
        </Stack>
      </Collapse>
      <Modal
        opened={browserOpen}
        onClose={() => setBrowserOpen(false)}
        title={
          browserTarget?.kind === 'table'
            ? 'Choose lookup table'
            : browserTarget?.kind === 'lookup'
              ? 'Choose lookup value column'
              : 'Choose source column'
        }
        size="md"
      >
        <Text size="sm" c="dimmed" mb="sm">
          {browserTarget?.kind === 'table'
            ? 'Pick a governed lookup table. You can still type any lookup table name manually.'
            : browserTarget?.kind === 'lookup'
            ? 'Pick the value column from the lookup row. You can still type any lookup column manually.'
            : 'Pick a source column to drive the lookup hash. You can still type any column name manually.'}
        </Text>
        <div className="masking-column-browser">
          {(browserTarget?.kind === 'table' ? tableChoices : browserTarget?.kind === 'lookup' ? lookupColumnChoices : columnChoices).map((column) => (
            <button key={column} type="button" onClick={() => chooseBrowserColumn(column)}>
              <Text fw={750}>{column}</Text>
              <Text size="xs" c="dimmed">
                {browserTarget?.kind === 'table'
                  ? `${mode.toUpperCase()} lookup table`
                  : browserTarget?.kind === 'lookup'
                    ? 'Lookup table value column'
                    : 'Row-context source column'}
              </Text>
            </button>
          ))}
          {browserTarget?.kind === 'table' && !tableChoices.length ? (
            <Text c="dimmed" size="sm">No governed {mode} lookup tables were returned. Type the lookup table name manually.</Text>
          ) : null}
        </div>
      </Modal>
    </div>
  );
}

export function FunctionCard({
  name,
  active,
  onSelect
}: {
  name: string;
  active?: boolean;
  onSelect: (name: string) => void;
}) {
  return (
    <button className={`masking-function-card ${active ? 'is-active' : ''}`} onClick={() => onSelect(name)} type="button">
      <Group justify="space-between" align="flex-start" gap="sm" wrap="nowrap">
        <div>
          <Text fw={780}>{name}</Text>
          <Text size="xs" c="dimmed">
            {functionSummary(name)}
          </Text>
        </div>
        <ThemeIcon variant="light" color={active ? 'blue' : 'gray'} size={30}>
          {name === 'SCRIPT' ? <IconSparkles size={16} /> : <IconShieldCheck size={16} />}
        </ThemeIcon>
      </Group>
      <Group gap={6} mt={8}>
        <Badge variant="light" color="gray">{functionCategory(name)}</Badge>
        {maskParamLabel(name, 1) ? <Badge variant="light">param1</Badge> : null}
        {maskParamLabel(name, 2) ? <Badge variant="light">param2</Badge> : null}
        {!maskParamLabel(name, 1) && !maskParamLabel(name, 2) ? <Badge variant="light" color="gray">no params</Badge> : null}
        <span className="masking-function-try">Try</span>
      </Group>
    </button>
  );
}

export function PreviewResult({ original, masked }: { original?: string | null; masked?: string | null }) {
  if (original == null && masked == null) return null;
  return (
    <div className="masking-preview-result">
      <code style={{ whiteSpace: 'pre-wrap' }}>{original || '(empty)'}</code>
      <IconArrowRight size={18} />
      <code className="is-masked" style={{ whiteSpace: 'pre-wrap' }}>{masked || '(null)'}</code>
    </div>
  );
}

export function EmptyPanel({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return (
    <Paper className="masking-empty" p="xl">
      <Text fw={780}>{title}</Text>
      <Text c="dimmed" size="sm" maw={560}>
        {detail}
      </Text>
      {action ? <div>{action}</div> : null}
    </Paper>
  );
}

export function InlineDanger({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <Button size="compact-xs" variant="subtle" color="red" onClick={onClick}>
      {children}
    </Button>
  );
}

function shortLabel(label: string) {
  if (label === 'Part: CITY/STATE/ZIP/FULL') return 'Part';
  if (label.startsWith('Script name')) return 'Script';
  return label.replace('Output ', '').replace(' handling', '');
}
