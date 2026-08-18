'use client';

import {
  ActionIcon,
  Badge,
  Button,
  Checkbox,
  Group,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  TextInput,
  Tooltip
} from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';

import type { DataSource } from '@/lib/types';
import type {
  GeneratorSpec,
  SyntheticAssetSummary,
  SyntheticAssetType
} from '../types';

type AssetObject = Record<string, unknown>;

export function SyntheticAssetDefinitionForm({
  assetType,
  value,
  assets,
  generators,
  dataSources,
  disabled,
  onChange
}: {
  assetType: SyntheticAssetType;
  value: AssetObject;
  assets: SyntheticAssetSummary[];
  generators: GeneratorSpec[];
  dataSources: DataSource[];
  disabled: boolean;
  onChange: (value: AssetObject) => void;
}) {
  if (assetType === 'DATA_MODEL') {
    return (
      <DataModelForm
        value={value}
        assets={assets}
        generators={generators}
        disabled={disabled}
        onChange={onChange}
      />
    );
  }
  if (assetType === 'FIELD_CONTRACT') {
    return <FieldContractForm value={value} disabled={disabled} onChange={onChange} />;
  }
  if (assetType === 'GENERATION_RULE') {
    return <GenerationRuleForm value={value} generators={generators} disabled={disabled} onChange={onChange} />;
  }
  if (assetType === 'DELIVERY_PROFILE') {
    return <DeliveryProfileForm value={value} dataSources={dataSources} disabled={disabled} onChange={onChange} />;
  }
  return <ScenarioExecutionForm value={value} disabled={disabled} onChange={onChange} />;
}

function FieldContractForm({
  value,
  disabled,
  onChange
}: {
  value: AssetObject;
  disabled: boolean;
  onChange: (value: AssetObject) => void;
}) {
  const update = (patch: AssetObject) => onChange({ ...value, ...patch });
  return (
    <section className="synthetic-guided-section">
      <SectionHeading title="Field requirements" detail="Define one reusable semantic and database contract." />
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <TextInput label="Semantic type" value={text(value.semanticType)} disabled={disabled} onChange={(event) => update({ semanticType: event.currentTarget.value })} placeholder="CUSTOMER_IDENTIFIER" />
        <TextInput label="SQL type" value={text(value.sqlType)} disabled={disabled} onChange={(event) => update({ sqlType: event.currentTarget.value })} placeholder="VARCHAR(40)" required />
        <TextInput label="Field description" value={text(value.description)} disabled={disabled} onChange={(event) => update({ description: event.currentTarget.value })} placeholder="Stable customer identifier" />
        <Group align="flex-end" gap="xl">
          <Switch label="Required" checked={flag(value.required)} disabled={disabled} onChange={(event) => update({ required: event.currentTarget.checked })} />
          <Switch label="Unique" checked={flag(value.unique)} disabled={disabled} onChange={(event) => update({ unique: event.currentTarget.checked })} />
        </Group>
      </SimpleGrid>
    </section>
  );
}

function GenerationRuleForm({
  value,
  generators,
  disabled,
  onChange
}: {
  value: AssetObject;
  generators: GeneratorSpec[];
  disabled: boolean;
  onChange: (value: AssetObject) => void;
}) {
  const update = (patch: AssetObject) => onChange({ ...value, ...patch });
  return (
    <section className="synthetic-guided-section">
      <SectionHeading title="Generation behavior" detail="Configure a reusable deterministic value rule." />
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Select
          label="Generator"
          searchable
          required
          disabled={disabled}
          data={generatorOptions(generators)}
          value={text(value.generator) || null}
          onChange={(next) => update({ generator: next || '' })}
        />
        <TextInput label="Output SQL type" value={text(value.outputType)} disabled={disabled} onChange={(event) => update({ outputType: event.currentTarget.value })} placeholder="VARCHAR" />
        <TextInput label="Parameter 1" value={text(value.param1)} disabled={disabled} onChange={(event) => update({ param1: event.currentTarget.value })} placeholder="Generator-specific value" />
        <TextInput label="Parameter 2" value={text(value.param2)} disabled={disabled} onChange={(event) => update({ param2: event.currentTarget.value })} placeholder="Generator-specific value" />
      </SimpleGrid>
      <Switch mt="md" label="Deterministic for the same seed" checked={value.deterministic !== false} disabled={disabled} onChange={(event) => update({ deterministic: event.currentTarget.checked })} />
    </section>
  );
}

function DeliveryProfileForm({
  value,
  dataSources,
  disabled,
  onChange
}: {
  value: AssetObject;
  dataSources: DataSource[];
  disabled: boolean;
  onChange: (value: AssetObject) => void;
}) {
  const receiver = text(value.receiver) || 'CSV';
  const update = (patch: AssetObject) => onChange({ ...value, ...patch });
  const keyColumns = list(value.keyColumns).map(String).join(', ');
  return (
    <Stack gap="md">
      <section className="synthetic-guided-section">
        <SectionHeading title="Destination" detail="Choose where generated rows are delivered." />
        <SimpleGrid cols={{ base: 1, md: 2 }}>
          <Select
            label="Receiver"
            data={[
              { value: 'DB', label: 'Database' },
              { value: 'CSV', label: 'CSV files' },
              { value: 'JSON', label: 'JSON files' },
              { value: 'SQL', label: 'SQL script' }
            ]}
            value={receiver}
            disabled={disabled}
            onChange={(next) => update({ receiver: next || 'CSV' })}
          />
          {receiver === 'DB' ? (
            <Select
              label="Target data source"
              searchable
              required
              disabled={disabled}
              data={dataSources
                .filter((source) => source.role !== 'SOURCE')
                .map((source) => ({ value: String(source.id), label: `${source.name} / ${source.kind}` }))}
              value={value.targetDataSourceId == null ? null : String(value.targetDataSourceId)}
              onChange={(next) => update({ targetDataSourceId: next ? Number(next) : null })}
            />
          ) : (
            <TextInput label="File name prefix" value={text(value.fileNamePrefix)} disabled={disabled} onChange={(event) => update({ fileNamePrefix: event.currentTarget.value })} placeholder="customer-test-data" />
          )}
          {receiver === 'DB' ? (
            <TextInput label="Target schema" value={text(value.targetSchema)} disabled={disabled} onChange={(event) => update({ targetSchema: event.currentTarget.value })} placeholder="public" />
          ) : null}
        </SimpleGrid>
      </section>

      <section className="synthetic-guided-section">
        <SectionHeading title="Load behavior" detail="Control preparation, writes, batching, and failure handling." />
        <SimpleGrid cols={{ base: 1, md: 3 }}>
          <Select label="Load action" disabled={disabled} value={text(value.loadAction) || 'INSERT'} data={['INSERT', 'UPDATE', 'UPSERT', 'REPLACE', 'TRUNCATE_ONLY']} onChange={(next) => update({ loadAction: next || 'INSERT' })} />
          <Select label="Target preparation" disabled={disabled} value={text(value.targetPrep) || 'NONE'} data={[{ value: 'NONE', label: 'Do not clear target' }, { value: 'DELETE', label: 'Delete rows first' }, { value: 'TRUNCATE', label: 'Truncate first' }]} onChange={(next) => update({ targetPrep: next || 'NONE' })} />
          <TextInput label="Key columns" disabled={disabled} value={keyColumns} onChange={(event) => update({ keyColumns: commaList(event.currentTarget.value) })} placeholder="id, account_id" />
          <NumberInput label="Batch size" min={1} disabled={disabled} value={numberOrBlank(value.batchSize)} onChange={(next) => update({ batchSize: optionalNumber(next) })} placeholder="Engine default" />
          <NumberInput label="Commit every rows" min={1} disabled={disabled} value={numberOrBlank(value.commitEveryRows)} onChange={(next) => update({ commitEveryRows: optionalNumber(next) })} placeholder="Engine default" />
          <NumberInput label="Maximum rejects" min={0} disabled={disabled} value={numberOrBlank(value.maxRejects)} onChange={(next) => update({ maxRejects: optionalNumber(next) })} placeholder="Stop on first error" />
        </SimpleGrid>
        <Group mt="md" gap="xl">
          <Switch label="Create missing tables" checked={flag(value.createTable)} disabled={disabled} onChange={(event) => update({ createTable: event.currentTarget.checked })} />
          <Switch label="Drop and recreate first" checked={flag(value.dropTable)} disabled={disabled} onChange={(event) => update({ dropTable: event.currentTarget.checked })} />
          <Switch label="Fast load" checked={flag(value.fastLoad)} disabled={disabled} onChange={(event) => update({ fastLoad: event.currentTarget.checked })} />
          <Switch label="Continue after bad rows" checked={flag(value.continueOnError)} disabled={disabled} onChange={(event) => update({ continueOnError: event.currentTarget.checked })} />
        </Group>
      </section>
    </Stack>
  );
}

function ScenarioExecutionForm({
  value,
  disabled,
  onChange
}: {
  value: AssetObject;
  disabled: boolean;
  onChange: (value: AssetObject) => void;
}) {
  const execution = object(value.execution);
  const updateExecution = (patch: AssetObject) => onChange({ ...value, execution: { ...execution, ...patch } });
  const mode = text(execution.mode) || 'SINGLE';
  return (
    <section className="synthetic-guided-section">
      <SectionHeading title="Execution" detail="Choose single-stream or partitioned generation." />
      <SimpleGrid cols={{ base: 1, md: 3 }}>
        <Select label="Execution mode" value={mode} disabled={disabled} data={[{ value: 'SINGLE', label: 'Single worker' }, { value: 'LOCAL_PARTITIONED', label: 'Local partitioned workers' }]} onChange={(next) => updateExecution({ mode: next || 'SINGLE' })} />
        <NumberInput label="Worker count" min={1} disabled={disabled || mode === 'SINGLE'} value={numberOrBlank(execution.partitionCount)} onChange={(next) => updateExecution({ partitionCount: optionalNumber(next) })} />
        <NumberInput label="Rows per partition" min={1} disabled={disabled || mode === 'SINGLE'} value={numberOrBlank(execution.partitionSize)} onChange={(next) => updateExecution({ partitionSize: optionalNumber(next) })} />
      </SimpleGrid>
    </section>
  );
}

function DataModelForm({
  value,
  assets,
  generators,
  disabled,
  onChange
}: {
  value: AssetObject;
  assets: SyntheticAssetSummary[];
  generators: GeneratorSpec[];
  disabled: boolean;
  onChange: (value: AssetObject) => void;
}) {
  const tables = list(value.tables).map(object);
  const contracts = publishedAssets(assets, 'FIELD_CONTRACT');
  const rules = publishedAssets(assets, 'GENERATION_RULE');
  const setTables = (next: AssetObject[]) => onChange({ ...value, tables: next });
  const updateTable = (index: number, patch: AssetObject) => setTables(tables.map((table, tableIndex) => tableIndex === index ? { ...table, ...patch } : table));
  const removeTable = (index: number) => setTables(tables.filter((_, tableIndex) => tableIndex !== index));
  const addTable = () => setTables([...tables, { name: `table_${tables.length + 1}`, rowCount: 100, columns: [{ name: 'id', sqlType: 'BIGINT', primaryKey: true, generator: 'SEQUENCE' }] }]);

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <SectionHeading title="Tables and fields" detail="Define the generated shape, keys, relationships, and row volumes." />
        <Button size="xs" variant="light" leftSection={<IconPlus size={15} />} disabled={disabled} onClick={addTable}>Add table</Button>
      </Group>
      {tables.map((table, tableIndex) => {
        const columns = list(table.columns).map(object);
        const setColumns = (next: AssetObject[]) => updateTable(tableIndex, { columns: next });
        const updateColumn = (columnIndex: number, patch: AssetObject) => setColumns(columns.map((column, index) => index === columnIndex ? { ...column, ...patch } : column));
        return (
          <section className="synthetic-model-table" key={`${tableIndex}-${text(table.name)}`}>
            <header>
              <SimpleGrid cols={{ base: 1, sm: 2 }}>
                <TextInput label="Table name" value={text(table.name)} disabled={disabled} onChange={(event) => updateTable(tableIndex, { name: event.currentTarget.value })} />
                <NumberInput label="Rows to generate" min={0} value={numeric(table.rowCount, 100)} disabled={disabled} onChange={(next) => updateTable(tableIndex, { rowCount: numeric(next, 0) })} />
              </SimpleGrid>
              <Tooltip label="Remove table">
                <ActionIcon color="red" variant="subtle" disabled={disabled || tables.length === 1} onClick={() => removeTable(tableIndex)}><IconTrash size={17} /></ActionIcon>
              </Tooltip>
            </header>
            <div className="synthetic-model-column-head">
              <span>Field</span><span>SQL type</span><span>Generator or reusable rule</span><span>Parameters</span><span>Keys</span><span />
            </div>
            <Stack gap={6}>
              {columns.map((column, columnIndex) => {
                const ruleRef = object(column.ruleRef);
                const contractRef = object(column.fieldContractRef);
                return (
                  <div className="synthetic-model-column" key={`${columnIndex}-${text(column.name)}`}>
                    <TextInput aria-label="Field name" value={text(column.name)} disabled={disabled} onChange={(event) => updateColumn(columnIndex, { name: event.currentTarget.value })} placeholder="column_name" />
                    <div className="synthetic-model-type-fields">
                      <TextInput aria-label="SQL type" value={text(column.sqlType)} disabled={disabled} onChange={(event) => updateColumn(columnIndex, { sqlType: event.currentTarget.value })} placeholder="VARCHAR(80)" />
                      <Select
                        aria-label="Field contract"
                        placeholder="No field contract"
                        clearable
                        searchable
                        disabled={disabled}
                        data={contracts.map(assetOption)}
                        value={text(contractRef.assetId) || null}
                        onChange={(next) => updateColumn(columnIndex, { fieldContractRef: assetReference(contracts, next) })}
                      />
                    </div>
                    <div className="synthetic-model-generator-fields">
                      <Select
                        aria-label="Generator"
                        searchable
                        disabled={disabled || Boolean(ruleRef.assetId)}
                        data={generatorOptions(generators)}
                        value={text(column.generator) || null}
                        onChange={(next) => updateColumn(columnIndex, { generator: next || '' })}
                      />
                      <Select
                        aria-label="Reusable generation rule"
                        placeholder="No reusable rule"
                        clearable
                        searchable
                        disabled={disabled}
                        data={rules.map(assetOption)}
                        value={text(ruleRef.assetId) || null}
                        onChange={(next) => updateColumn(columnIndex, { ruleRef: assetReference(rules, next) })}
                      />
                    </div>
                    <div className="synthetic-model-param-fields">
                      <TextInput aria-label="Generator parameter 1" value={text(column.param1)} disabled={disabled || Boolean(ruleRef.assetId)} onChange={(event) => updateColumn(columnIndex, { param1: event.currentTarget.value })} placeholder="Param 1" />
                      <TextInput aria-label="Generator parameter 2" value={text(column.param2)} disabled={disabled || Boolean(ruleRef.assetId)} onChange={(event) => updateColumn(columnIndex, { param2: event.currentTarget.value })} placeholder="Param 2" />
                    </div>
                    <div className="synthetic-model-key-fields">
                      <Checkbox label="PK" checked={flag(column.primaryKey)} disabled={disabled} onChange={(event) => updateColumn(columnIndex, { primaryKey: event.currentTarget.checked })} />
                      <TextInput aria-label="Foreign key table" value={text(column.fkTable)} disabled={disabled} onChange={(event) => updateColumn(columnIndex, { fkTable: event.currentTarget.value })} placeholder="Parent table" />
                      <TextInput aria-label="Foreign key column" value={text(column.fkColumn)} disabled={disabled} onChange={(event) => updateColumn(columnIndex, { fkColumn: event.currentTarget.value })} placeholder="Parent field" />
                    </div>
                    <ActionIcon aria-label="Remove field" color="red" variant="subtle" disabled={disabled || columns.length === 1} onClick={() => setColumns(columns.filter((_, index) => index !== columnIndex))}><IconTrash size={16} /></ActionIcon>
                  </div>
                );
              })}
            </Stack>
            <Button mt="sm" size="compact-xs" variant="subtle" leftSection={<IconPlus size={13} />} disabled={disabled} onClick={() => setColumns([...columns, { name: `field_${columns.length + 1}`, sqlType: 'VARCHAR(255)', generator: 'ALPHANUMERIC' }])}>Add field</Button>
          </section>
        );
      })}
      {!tables.length ? <Button variant="light" leftSection={<IconPlus size={15} />} disabled={disabled} onClick={addTable}>Add the first table</Button> : null}
    </Stack>
  );
}

function SectionHeading({ title, detail }: { title: string; detail: string }) {
  return <div><Group gap="xs"><Text fw={850}>{title}</Text><Badge size="xs" variant="light">Guided</Badge></Group><Text size="xs" c="dimmed">{detail}</Text></div>;
}

function publishedAssets(assets: SyntheticAssetSummary[], type: SyntheticAssetType) {
  return assets.filter((asset) => asset.assetType === type && asset.currentVersion > 0 && asset.status !== 'ARCHIVED');
}

function assetOption(asset: SyntheticAssetSummary) {
  return { value: asset.id, label: `${asset.name} / v${asset.currentVersion}` };
}

function assetReference(assets: SyntheticAssetSummary[], id: string | null) {
  const asset = assets.find((candidate) => candidate.id === id);
  return asset ? { assetId: asset.id, version: asset.currentVersion } : undefined;
}

function generatorOptions(generators: GeneratorSpec[]) {
  return generators
    .map((generator) => {
      const value = String(generator.name || generator.id || '');
      return value ? { value, label: generator.label ? `${generator.label} (${value})` : value } : null;
    })
    .filter((option): option is { value: string; label: string } => Boolean(option));
}

function object(value: unknown): AssetObject {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as AssetObject : {};
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function text(value: unknown): string {
  return value == null ? '' : String(value);
}

function flag(value: unknown): boolean {
  return value === true;
}

function numeric(value: unknown, fallback: number): number {
  const result = Number(value);
  return Number.isFinite(result) ? result : fallback;
}

function optionalNumber(value: string | number): number | null {
  if (value === '') return null;
  const result = Number(value);
  return Number.isFinite(result) ? result : null;
}

function numberOrBlank(value: unknown): number | '' {
  return value == null || value === '' ? '' : numeric(value, 0);
}

function commaList(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}
