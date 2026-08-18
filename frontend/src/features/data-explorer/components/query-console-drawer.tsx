'use client';

import { useState } from 'react';
import { Alert, Badge, Button, Code, Drawer, Group, ScrollArea, SegmentedControl, Stack, Table, Text, Textarea } from '@mantine/core';
import { IconAlertTriangle, IconDownload, IconPlayerPlay } from '@tabler/icons-react';

import type { QueryResult } from '../types';

type QueryConsoleDrawerProps = {
  opened: boolean;
  tableName?: string | null;
  schema?: string | null;
  result?: QueryResult;
  running?: boolean;
  canWrite?: boolean;
  onClose: () => void;
  onRun: (sql: string, mode: 'read' | 'write') => void;
};

export function QueryConsoleDrawer({
  opened,
  tableName,
  schema,
  result,
  running,
  canWrite,
  onClose,
  onRun
}: QueryConsoleDrawerProps) {
  const [sql, setSql] = useState(
    tableName ? `SELECT * FROM ${schema ? `${schema}.` : ''}${tableName}` : ''
  );
  const [mode, setMode] = useState<'read' | 'write'>('read');

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="xl" title="SQL console">
      <Stack h="calc(100vh - 86px)" gap="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={750}>{mode === 'read' ? 'Inspect with SQL' : 'Execute a database change'}</Text>
            <Text size="sm" c="dimmed">One statement at a time, 30-second timeout. Query results are capped at 1,000 rows.</Text>
          </div>
          <SegmentedControl
            value={mode}
            onChange={(value) => setMode(value as 'read' | 'write')}
            data={[
              { value: 'read', label: 'Read' },
              { value: 'write', label: 'Create & modify', disabled: !canWrite }
            ]}
          />
        </Group>
        {mode === 'write' ? (
          <Alert color="orange" icon={<IconAlertTriangle size={17} />}>
            This mode changes the connected database. CREATE, ALTER, INSERT, UPDATE, DELETE, MERGE,
            TRUNCATE, and DROP are allowed after confirmation.
          </Alert>
        ) : null}
        <Textarea
          value={sql}
          onChange={(event) => setSql(event.currentTarget.value)}
          minRows={6}
          autosize
          styles={{ input: { fontFamily: 'var(--font-mono, monospace)' } }}
        />
        <Group>
          <Button
            color={mode === 'write' ? 'orange' : 'blue'}
            leftSection={<IconPlayerPlay size={16} />}
            onClick={() => onRun(sql, mode)}
            loading={running}
          >
            {mode === 'write' ? 'Review & execute' : 'Run query'}
          </Button>
          {result?.columns.length ? (
            <Button variant="default" leftSection={<IconDownload size={16} />} onClick={() => downloadCsv(result)}>
              Download CSV
            </Button>
          ) : null}
          {result ? (
            <Group gap="xs">
              {result.statementType ? <Badge variant="light">{result.statementType}</Badge> : null}
              <Text size="sm" c="dimmed">
                {typeof result.affectedRows === 'number' && result.affectedRows >= 0
                  ? `${result.affectedRows} row(s) affected`
                  : `${result.rowCount} rows`}
                {' · '}{result.elapsedMs} ms
              </Text>
            </Group>
          ) : null}
        </Group>
        <ScrollArea flex={1} offsetScrollbars>
          {result?.columns.length ? (
            <Table.ScrollContainer minWidth={Math.max(720, result.columns.length * 170)}>
              <Table striped highlightOnHover withTableBorder>
                <Table.Thead>
                  <Table.Tr>{result.columns.map((column) => <Table.Th key={column}>{column}</Table.Th>)}</Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {result.rows.map((row, index) => (
                    <Table.Tr key={index}>
                      {row.map((cell, cellIndex) => <Table.Td key={cellIndex}><Code>{display(cell)}</Code></Table.Td>)}
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          ) : result ? (
            <Stack align="center" justify="center" mih={220} gap="xs">
              <Badge color="green" variant="light">Statement completed</Badge>
              <Text fw={750}>
                {typeof result.affectedRows === 'number' && result.affectedRows >= 0
                  ? `${result.affectedRows} row(s) affected`
                  : 'Database object changed'}
              </Text>
              <Text c="dimmed" size="sm">The transaction was committed and recorded in the audit trail.</Text>
            </Stack>
          ) : (
            <Text c="dimmed" ta="center" mt="xl">Run a query to inspect results.</Text>
          )}
        </ScrollArea>
      </Stack>
    </Drawer>
  );
}

function display(value: unknown) {
  if (value == null) return 'NULL';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function downloadCsv(result: QueryResult) {
  const escape = (value: unknown) => `"${display(value).replaceAll('"', '""')}"`;
  const content = [result.columns.map(escape).join(','), ...result.rows.map((row) => row.map(escape).join(','))].join('\n');
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'forge-data-explorer.csv';
  link.click();
  URL.revokeObjectURL(url);
}
