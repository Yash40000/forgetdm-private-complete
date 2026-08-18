'use client';

import { Button, Drawer, Group, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useState } from 'react';

import { useTopologyActions } from '../hooks';
import type { TopologySummary } from '../types';

export function CreateTopologyDrawer({
  opened,
  onClose,
  onCreated
}: {
  opened: boolean;
  onClose: () => void;
  onCreated: (topology: TopologySummary) => void;
}) {
  const { create } = useTopologyActions();
  const [name, setName] = useState('');
  const [domain, setDomain] = useState('');
  const [description, setDescription] = useState('');
  const [visibility, setVisibility] = useState('GROUP');

  useEffect(() => {
    if (!opened) return;
    setName('');
    setDomain('');
    setDescription('');
    setVisibility('GROUP');
  }, [opened]);

  const valid = name.trim().length >= 8 && name.trim().length <= 120;
  const submit = async () => {
    try {
      const created = await create.mutateAsync({
        name: name.trim(),
        domain: domain.trim() || undefined,
        description: description.trim() || undefined,
        visibility
      });
      notifications.show({
        color: 'green',
        title: 'Topology created',
        message: `${created.name} is ready for source attachment.`
      });
      onCreated(created);
      onClose();
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not create topology',
        message: error instanceof Error ? error.message : 'Request failed'
      });
    }
  };

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size={460}
      title="Create data topology"
      overlayProps={{ backgroundOpacity: 0.38, blur: 2 }}
    >
      <Stack gap="lg">
        <div>
          <Text fw={700}>Start with intent, then attach systems</Text>
          <Text size="sm" c="dimmed">
            A topology is a governed map of schemas and declared relationships. It does not move data.
          </Text>
        </div>
        <TextInput
          label="Topology name"
          description="8-120 characters. Use a durable business or application name."
          value={name}
          maxLength={120}
          onChange={(event) => setName(event.currentTarget.value)}
          error={name.length > 0 && !valid ? 'Enter at least 8 characters' : undefined}
          autoFocus
        />
        <TextInput
          label="Domain"
          placeholder="Retail banking, cards, claims"
          value={domain}
          maxLength={80}
          onChange={(event) => setDomain(event.currentTarget.value)}
        />
        <Textarea
          label="Purpose"
          placeholder="What this map governs and who uses it"
          value={description}
          maxLength={1000}
          minRows={3}
          autosize
          onChange={(event) => setDescription(event.currentTarget.value)}
        />
        <Select
          label="Visibility"
          value={visibility}
          onChange={(value) => setVisibility(value || 'GROUP')}
          data={[
            { value: 'GROUP', label: 'My group' },
            { value: 'PRIVATE', label: 'Only me' },
            { value: 'SHARED', label: 'All authorized users' }
          ]}
          allowDeselect={false}
        />
        <Group justify="flex-end" mt="sm">
          <Button variant="subtle" color="gray" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={!valid} loading={create.isPending}>
            Create topology
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}
