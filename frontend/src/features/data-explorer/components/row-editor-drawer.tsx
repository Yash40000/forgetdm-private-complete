'use client';

import { useMemo, useState } from 'react';
import {
  Button,
  Drawer,
  Group,
  ScrollArea,
  Stack,
  Switch,
  Text,
  TextInput
} from '@mantine/core';

import type { ExplorerColumn } from '../types';

type RowEditorDrawerProps = {
  opened: boolean;
  mode: 'insert' | 'edit';
  columns: ExplorerColumn[];
  primaryKeys: string[];
  row?: Record<string, unknown> | null;
  submitting?: boolean;
  onClose: () => void;
  onSubmit: (values: Record<string, unknown>) => void;
};

export function RowEditorDrawer({
  opened,
  mode,
  columns,
  primaryKeys,
  row,
  submitting,
  onClose,
  onSubmit
}: RowEditorDrawerProps) {
  const [values, setValues] = useState<Record<string, string | null>>(() =>
    initialValues(mode, columns, row)
  );
  const [touched, setTouched] = useState<Set<string>>(new Set());
  const keySet = useMemo(() => new Set(primaryKeys.map((key) => key.toLowerCase())), [primaryKeys]);

  const writable = columns.filter(
    (column) => !column.generated && !column.autoIncrement && !(mode === 'edit' && keySet.has(column.column.toLowerCase()))
  );

  const submit = () => {
    const payload: Record<string, unknown> = {};
    touched.forEach((column) => {
      payload[column] = values[column] ?? null;
    });
    onSubmit(payload);
  };

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="lg"
      title={mode === 'insert' ? 'Add row' : 'Edit row'}
      className="data-explorer-editor"
    >
      <Stack gap="md" h="calc(100vh - 86px)">
        <div>
          <Text fw={750}>{mode === 'insert' ? 'Create one database row' : 'Update selected row'}</Text>
          <Text size="sm" c="dimmed">
            {mode === 'insert'
              ? 'Only fields you touch are sent. Database defaults remain active.'
              : 'Primary-key fields identify the row and cannot be changed here.'}
          </Text>
        </div>
        <ScrollArea flex={1} offsetScrollbars>
          <Stack gap="sm" pr="sm">
            {mode === 'edit' && primaryKeys.map((key) => (
              <TextInput
                key={key}
                label={key}
                description="Primary key"
                value={String(valueFor(row || {}, key) ?? '')}
                disabled
              />
            ))}
            {writable.map((column) => {
              const value = values[column.column];
              const isNull = touched.has(column.column) && value === null;
              return (
                <div className="data-explorer-field" key={column.column}>
                  <Group justify="space-between" align="flex-start" wrap="nowrap">
                    <TextInput
                      label={column.column}
                      description={columnDescription(column)}
                      value={value ?? ''}
                      disabled={isNull}
                      onChange={(event) => {
                        const next = event.currentTarget.value;
                        setValues((current) => ({ ...current, [column.column]: next }));
                        setTouched((current) => new Set(current).add(column.column));
                      }}
                      flex={1}
                    />
                    {column.nullable ? (
                      <Switch
                        mt={28}
                        label="NULL"
                        checked={isNull}
                        onChange={(event) => {
                          const checked = event.currentTarget.checked;
                          setValues((current) => ({ ...current, [column.column]: checked ? null : '' }));
                          setTouched((current) => new Set(current).add(column.column));
                        }}
                      />
                    ) : null}
                  </Group>
                </div>
              );
            })}
          </Stack>
        </ScrollArea>
        <Group justify="flex-end" className="data-explorer-drawer-actions">
          <Button variant="subtle" color="gray" onClick={onClose}>Discard</Button>
          <Button onClick={submit} loading={submitting} disabled={touched.size === 0}>
            {mode === 'insert' ? 'Insert row' : 'Save changes'}
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

function valueFor(row: Record<string, unknown>, column: string) {
  const key = Object.keys(row).find((candidate) => candidate.toLowerCase() === column.toLowerCase());
  return key ? row[key] : undefined;
}

function initialValues(
  mode: 'insert' | 'edit',
  columns: ExplorerColumn[],
  row?: Record<string, unknown> | null
) {
  const next: Record<string, string | null> = {};
  if (mode === 'edit' && row) {
    columns.forEach((column) => {
      const value = valueFor(row, column.column);
      next[column.column] = value == null ? null : String(value);
    });
  }
  return next;
}

function columnDescription(column: ExplorerColumn) {
  const parts = [column.type || 'database type'];
  if (column.size) parts.push(String(column.size));
  if (!column.nullable) parts.push('required');
  if (column.defaultValue != null) parts.push(`default ${column.defaultValue}`);
  return parts.join(' · ');
}
