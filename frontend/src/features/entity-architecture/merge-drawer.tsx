'use client';

import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Divider, Drawer, Group, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';

import { fetchColumns } from '@/features/synthetic/hooks';
import { keys } from '@/lib/keys';
import type { IdentityAnchor, LoadedApplication } from './types';

export type EntityMergeDraft = {
  name: string;
  description: string;
  domain: string;
  primarySliceId: string;
  anchors: IdentityAnchor[];
};

type Props = {
  opened: boolean;
  applications: LoadedApplication[];
  initialAnchors: IdentityAnchor[];
  saving: boolean;
  onClose: () => void;
  onSave: (draft: EntityMergeDraft) => void;
};

export function MergeDrawer({ opened, applications, initialAnchors, saving, onClose, onSave }: Props) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [domain, setDomain] = useState('');
  const [primarySliceId, setPrimarySliceId] = useState('');
  const [anchors, setAnchors] = useState<IdentityAnchor[]>([]);

  useEffect(() => {
    if (!opened) return;
    const available = new Map(applications.map((application) => [application.id, application]));
    setPrimarySliceId((current) => available.has(current) ? current : applications[0]?.id || '');
    setAnchors(applications.map((application) => {
      const existing = initialAnchors.find((anchor) => anchor.sliceId === application.id);
      return existing || { sliceId: application.id, table: application.tables[0] || '', column: '' };
    }));
  }, [applications, initialAnchors, opened]);

  const appOptions = applications.map((application) => ({ value: application.id, label: application.label }));
  const ready = name.trim().length >= 8 && name.trim().length <= 120 && primarySliceId
    && anchors.length === applications.length && anchors.every((anchor) => anchor.table && anchor.column);

  const updateAnchor = (next: IdentityAnchor) => {
    setAnchors((current) => current.map((anchor) => anchor.sliceId === next.sliceId ? next : anchor));
  };

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="lg" title="Merge as one business entity">
      <Stack gap="lg">
        <Alert icon={<IconInfoCircle size={17} />} color="blue" variant="light">
          Map each application&apos;s authoritative identity key. ForgeTDM will not assume that similarly named columns represent the same customer.
        </Alert>
        <TextInput
          label="Entity name"
          placeholder="Customer financial ecosystem"
          description="8–120 characters"
          value={name}
          onChange={(event) => setName(event.currentTarget.value)}
          minLength={8}
          maxLength={120}
          spellCheck={false}
        />
        <Group grow align="start">
          <TextInput label="Domain" placeholder="Retail Banking" value={domain} onChange={(event) => setDomain(event.currentTarget.value)} />
          <Select
            label="Primary application"
            description="Owns the canonical root and business key."
            data={appOptions}
            value={primarySliceId}
            onChange={(value) => setPrimarySliceId(value || '')}
          />
        </Group>
        <TextInput
          label="Description"
          placeholder="Optional architecture context"
          value={description}
          onChange={(event) => setDescription(event.currentTarget.value)}
          maxLength={500}
        />

        <Divider label="Identity crosswalk" labelPosition="left" />
        <Stack gap="sm">
          {applications.map((application) => (
            <IdentityAnchorEditor
              key={application.id}
              application={application}
              anchor={anchors.find((anchor) => anchor.sliceId === application.id)}
              primary={application.id === primarySliceId}
              onChange={updateAnchor}
            />
          ))}
        </Stack>

        <Group justify="flex-end" mt="sm">
          <Button variant="subtle" color="gray" onClick={onClose}>Cancel</Button>
          <Button
            loading={saving}
            disabled={!ready}
            onClick={() => onSave({
              name: name.trim(),
              description: description.trim(),
              domain: domain.trim(),
              primarySliceId,
              anchors
            })}
          >
            Create business entity
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

function IdentityAnchorEditor({ application, anchor, primary, onChange }: {
  application: LoadedApplication;
  anchor?: IdentityAnchor;
  primary: boolean;
  onChange: (anchor: IdentityAnchor) => void;
}) {
  const table = anchor?.table || application.tables[0] || '';
  const columns = useQuery({
    queryKey: keys.dataSources.columns(application.dataSourceId, table, application.schema),
    enabled: Boolean(table),
    queryFn: () => fetchColumns(application.dataSourceId, application.schema, table)
  });
  const tableOptions = application.tables.map((value) => ({ value, label: value }));
  const columnOptions = useMemo(
    () => (columns.data || []).map((column) => ({ value: column.column, label: `${column.column}${column.type ? ` · ${column.type}` : ''}` })),
    [columns.data]
  );

  return (
    <div className="entity-architecture-anchor-row">
      <Group justify="space-between" mb="xs">
        <div>
          <Text fw={700} size="sm">{application.label}</Text>
          <Text c="dimmed" size="xs">{application.dataSourceName} · {application.schema}</Text>
        </div>
        {primary ? <Text className="entity-architecture-primary-label">PRIMARY</Text> : null}
      </Group>
      <Group grow align="start">
        <Select
          searchable
          label="Root table"
          data={tableOptions}
          value={table}
          onChange={(value) => onChange({ sliceId: application.id, table: value || '', column: '' })}
        />
        <Select
          searchable
          label="Identity key"
          placeholder={columns.isLoading ? 'Loading columns…' : 'Select key column'}
          data={columnOptions}
          value={anchor?.column || null}
          disabled={!table || columns.isLoading}
          onChange={(value) => onChange({ sliceId: application.id, table, column: value || '' })}
          nothingFoundMessage="No columns found"
        />
      </Group>
    </div>
  );
}
