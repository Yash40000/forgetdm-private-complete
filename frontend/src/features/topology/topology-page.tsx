'use client';

import {
  Alert,
  Button,
  Group,
  Loader,
  Text,
  TextInput
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconNetwork,
  IconPlus,
  IconSearch,
  IconSparkles
} from '@tabler/icons-react';
import { useMemo, useState } from 'react';

import { CreateTopologyDrawer } from './components/create-topology-drawer';
import { TopologyCatalog } from './components/topology-catalog';
import { TopologyWorkspace } from './components/topology-workspace';
import { useTopologies, useTopologyActions } from './hooks';
import type { TopologySummary } from './types';

export function TopologyPage() {
  const topologiesQuery = useTopologies();
  const actions = useTopologyActions();
  const [search, setSearch] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [selected, setSelected] = useState<TopologySummary | null>(null);

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return topologiesQuery.data || [];
    return (topologiesQuery.data || []).filter((topology) =>
      `${topology.name} ${topology.domain || ''} ${topology.description || ''}`
        .toLowerCase()
        .includes(query)
    );
  }, [search, topologiesQuery.data]);

  const remove = async (topology: TopologySummary) => {
    const confirmed = window.confirm(
      `Delete ${topology.name}? Its topology versions and discovery evidence will also be deleted. Source databases are not changed.`
    );
    if (!confirmed) return;
    try {
      await actions.remove.mutateAsync(topology.id);
      notifications.show({ color: 'green', message: `${topology.name} deleted` });
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not delete topology',
        message: error instanceof Error ? error.message : 'Request failed'
      });
    }
  };

  const loadExample = async () => {
    try {
      const sample = await actions.createSample.mutateAsync();
      notifications.show({
        color: 'blue',
        title: 'Example topology is running',
        message: 'The workspace will show live discovery progress.'
      });
      setSelected(sample.topology);
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not load example',
        message: error instanceof Error ? error.message : 'Request failed'
      });
    }
  };

  return (
    <main className="forge-page topology-page">
      <header className="topology-page-head">
        <Group gap="sm" wrap="nowrap">
          <span className="topology-page-mark">
            <IconNetwork size={21} />
          </span>
          <div>
            <Text component="h1" fw={850} size="xl">
              Data Topology
            </Text>
            <Text size="sm" c="dimmed">
              Govern how application schemas and relationships fit together before design or provisioning.
            </Text>
          </div>
        </Group>
        <Group gap="xs">
          <Button
            variant="light"
            leftSection={<IconSparkles size={16} />}
            onClick={loadExample}
            loading={actions.createSample.isPending}
          >
            Load example
          </Button>
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreateOpen(true)}>
            New topology
          </Button>
        </Group>
      </header>

      <section className="forge-card topology-catalog-shell">
        <div className="topology-catalog-toolbar">
          <div>
            <Text fw={750}>Topology catalog</Text>
            <Text size="xs" c="dimmed">
              One row per governed map. Open a row for its focused workflow.
            </Text>
          </div>
          <TextInput
            leftSection={<IconSearch size={15} />}
            placeholder="Search topology, domain, or purpose"
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            w={360}
          />
        </div>

        {topologiesQuery.isPending ? (
          <div className="topology-catalog-loading">
            <Loader size="sm" />
            <Text size="sm" c="dimmed">
              Loading topology catalog
            </Text>
          </div>
        ) : topologiesQuery.isError ? (
          <Alert color="red" icon={<IconAlertTriangle size={18} />} title="Topology catalog unavailable">
            {topologiesQuery.error instanceof Error ? topologiesQuery.error.message : 'Request failed'}
          </Alert>
        ) : filtered.length ? (
          <TopologyCatalog topologies={filtered} onOpen={setSelected} onDelete={remove} />
        ) : (
          <div className="topology-empty">
            <IconNetwork size={30} />
            <Text fw={700}>{search ? 'No matching topologies' : 'No topology models yet'}</Text>
            <Text size="sm" c="dimmed">
              {search
                ? 'Try a broader search.'
                : 'Create the first map, attach source schemas, and run governed discovery.'}
            </Text>
            {!search ? (
              <Group gap="xs">
                <Button
                  variant="light"
                  leftSection={<IconSparkles size={16} />}
                  onClick={loadExample}
                  loading={actions.createSample.isPending}
                >
                  Load example
                </Button>
                <Button leftSection={<IconPlus size={16} />} onClick={() => setCreateOpen(true)}>
                  Create topology
                </Button>
              </Group>
            ) : null}
          </div>
        )}
      </section>

      <CreateTopologyDrawer
        opened={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={setSelected}
      />
      {selected ? <TopologyWorkspace initial={selected} onClose={() => setSelected(null)} /> : null}
    </main>
  );
}
