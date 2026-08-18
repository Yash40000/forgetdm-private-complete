'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Divider,
  Drawer,
  Group,
  Select,
  Stack,
  Text,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowRight, IconInfoCircle, IconLink, IconTrash } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';

import { fetchColumns } from '@/features/synthetic/hooks';
import { keys } from '@/lib/keys';
import type { CrossDatabaseLink, CrossDatabaseLinkKind, LoadedApplication } from './types';

type Props = {
  opened: boolean;
  applications: LoadedApplication[];
  links: CrossDatabaseLink[];
  onClose: () => void;
  onChange: (links: CrossDatabaseLink[]) => void;
};

const LINK_KINDS: Array<{ value: CrossDatabaseLinkKind; label: string }> = [
  { value: 'PARENT_CHILD', label: 'Parent to child' },
  { value: 'SAME_AS', label: 'Same business identity' },
  { value: 'REFERENCE', label: 'Cross-system reference' }
];

export function CrossLinkDrawer({ opened, applications, links, onClose, onChange }: Props) {
  const [parentSliceId, setParentSliceId] = useState('');
  const [parentTable, setParentTable] = useState('');
  const [parentColumn, setParentColumn] = useState('');
  const [childSliceId, setChildSliceId] = useState('');
  const [childTable, setChildTable] = useState('');
  const [childColumn, setChildColumn] = useState('');
  const [kind, setKind] = useState<CrossDatabaseLinkKind>('PARENT_CHILD');

  useEffect(() => {
    if (!opened) return;
    setParentSliceId((current) => applications.some((item) => item.id === current) ? current : applications[0]?.id || '');
    setChildSliceId((current) => applications.some((item) => item.id === current)
      ? current
      : applications[1]?.id || applications[0]?.id || '');
  }, [applications, opened]);

  const parentApplication = applications.find((item) => item.id === parentSliceId);
  const childApplication = applications.find((item) => item.id === childSliceId);
  const parentColumns = useQuery({
    queryKey: keys.dataSources.columns(parentApplication?.dataSourceId || 0, parentTable, parentApplication?.schema || ''),
    enabled: Boolean(parentApplication && parentTable),
    queryFn: () => fetchColumns(parentApplication!.dataSourceId, parentApplication!.schema, parentTable)
  });
  const childColumns = useQuery({
    queryKey: keys.dataSources.columns(childApplication?.dataSourceId || 0, childTable, childApplication?.schema || ''),
    enabled: Boolean(childApplication && childTable),
    queryFn: () => fetchColumns(childApplication!.dataSourceId, childApplication!.schema, childTable)
  });

  const applicationOptions = applications.map((application) => ({ value: application.id, label: application.label }));
  const parentTableOptions = (parentApplication?.tables || []).map((value) => ({ value, label: value }));
  const childTableOptions = (childApplication?.tables || []).map((value) => ({ value, label: value }));
  const parentColumnOptions = useMemo(() => (parentColumns.data || []).map((column) => ({
    value: column.column,
    label: `${column.column}${column.type ? ` - ${column.type}` : ''}`
  })), [parentColumns.data]);
  const childColumnOptions = useMemo(() => (childColumns.data || []).map((column) => ({
    value: column.column,
    label: `${column.column}${column.type ? ` - ${column.type}` : ''}`
  })), [childColumns.data]);

  const ready = Boolean(
    parentApplication && childApplication
    && parentTable && parentColumn && childTable && childColumn
    && (parentSliceId !== childSliceId || normalize(parentTable) !== normalize(childTable))
  );

  const addLink = () => {
    if (!ready) return;
    const duplicate = links.some((link) =>
      link.parentSliceId === parentSliceId
      && normalize(link.parentTable) === normalize(parentTable)
      && normalize(link.parentColumn) === normalize(parentColumn)
      && link.childSliceId === childSliceId
      && normalize(link.childTable) === normalize(childTable)
      && normalize(link.childColumn) === normalize(childColumn)
    );
    if (duplicate) {
      notifications.show({ color: 'yellow', title: 'Link already exists', message: 'This exact cross-database relationship is already on the canvas.' });
      return;
    }
    const next: CrossDatabaseLink = {
      id: globalThis.crypto?.randomUUID?.() || `cross-${Date.now()}`,
      parentSliceId,
      parentTable,
      parentColumn,
      childSliceId,
      childTable,
      childColumn,
      kind
    };
    onChange([...links, next]);
    setChildTable('');
    setChildColumn('');
  };

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="xl" title="Cross-database relationships">
      <Stack gap="lg">
        <Alert icon={<IconInfoCircle size={17} />} color="blue" variant="light">
          Define the authoritative relationship between selected tables. These links drive canvas tracing and are retained as business-entity relationship evidence.
        </Alert>

        <div className="entity-architecture-link-builder">
          <Text fw={760} mb="sm">Source endpoint</Text>
          <Group grow align="start">
            <Select
              searchable
              label="Application"
              data={applicationOptions}
              value={parentSliceId}
              onChange={(value) => {
                setParentSliceId(value || '');
                setParentTable('');
                setParentColumn('');
              }}
            />
            <Select
              searchable
              label="Table"
              data={parentTableOptions}
              value={parentTable || null}
              disabled={!parentApplication}
              onChange={(value) => {
                setParentTable(value || '');
                setParentColumn('');
              }}
            />
            <Select
              searchable
              label="Source column"
              data={parentColumnOptions}
              value={parentColumn || null}
              disabled={!parentTable || parentColumns.isLoading}
              placeholder={parentColumns.isLoading ? 'Loading columns...' : 'Select column'}
              onChange={(value) => setParentColumn(value || '')}
            />
          </Group>

          <div className="entity-architecture-link-direction" aria-hidden="true">
            <IconArrowRight size={18} />
          </div>

          <Text fw={760} mb="sm">Related endpoint</Text>
          <Group grow align="start">
            <Select
              searchable
              label="Application"
              data={applicationOptions}
              value={childSliceId}
              onChange={(value) => {
                setChildSliceId(value || '');
                setChildTable('');
                setChildColumn('');
              }}
            />
            <Select
              searchable
              label="Table"
              data={childTableOptions}
              value={childTable || null}
              disabled={!childApplication}
              onChange={(value) => {
                setChildTable(value || '');
                setChildColumn('');
              }}
            />
            <Select
              searchable
              label="Related column"
              data={childColumnOptions}
              value={childColumn || null}
              disabled={!childTable || childColumns.isLoading}
              placeholder={childColumns.isLoading ? 'Loading columns...' : 'Select column'}
              onChange={(value) => setChildColumn(value || '')}
            />
          </Group>

          <Group justify="space-between" align="end" mt="md">
            <Select
              className="entity-architecture-link-kind"
              label="Relationship meaning"
              data={LINK_KINDS}
              value={kind}
              onChange={(value) => setKind((value as CrossDatabaseLinkKind) || 'PARENT_CHILD')}
            />
            <Button leftSection={<IconLink size={16} />} disabled={!ready} onClick={addLink}>Add relationship</Button>
          </Group>
          {parentSliceId && childSliceId && parentSliceId === childSliceId && normalize(parentTable) === normalize(childTable) ? (
            <Text c="red" size="xs" mt="xs">Choose two different tables. Relationships can use any columns and may stay inside one application or cross applications.</Text>
          ) : null}
        </div>

        <Divider label={`Relationships on canvas (${links.length})`} labelPosition="left" />
        {links.length ? (
          <Stack gap="xs">
            {links.map((link) => {
              const parent = applications.find((item) => item.id === link.parentSliceId);
              const child = applications.find((item) => item.id === link.childSliceId);
              return (
                <div className="entity-architecture-link-row" key={link.id}>
                  <div className="entity-architecture-link-endpoint">
                    <Text fw={700} size="sm">{parent?.label || 'Missing application'}</Text>
                    <Text c="dimmed" size="xs">{link.parentTable}.{link.parentColumn}</Text>
                  </div>
                  <div className="entity-architecture-link-middle">
                    <Badge variant="light" color="teal">{linkKindLabel(link.kind)}</Badge>
                    <IconArrowRight size={16} />
                  </div>
                  <div className="entity-architecture-link-endpoint">
                    <Text fw={700} size="sm">{child?.label || 'Missing application'}</Text>
                    <Text c="dimmed" size="xs">{link.childTable}.{link.childColumn}</Text>
                  </div>
                  <Tooltip label="Remove relationship">
                    <ActionIcon color="red" variant="subtle" aria-label="Remove cross-database relationship" onClick={() => onChange(links.filter((item) => item.id !== link.id))}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip>
                </div>
              );
            })}
          </Stack>
        ) : (
          <Text c="dimmed" size="sm">No cross-database relationships have been defined.</Text>
        )}

        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Close</Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

function normalize(value: string) {
  return value.trim().toLowerCase();
}

function linkKindLabel(kind: CrossDatabaseLinkKind) {
  return LINK_KINDS.find((item) => item.value === kind)?.label || kind;
}
