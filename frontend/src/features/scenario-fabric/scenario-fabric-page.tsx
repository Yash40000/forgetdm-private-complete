'use client';

import {
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  TextInput
} from '@mantine/core';
import {
  IconArrowRight,
  IconClipboardCheck,
  IconForms,
  IconNetwork,
  IconPlus,
  IconSearch,
  IconSparkles
} from '@tabler/icons-react';
import { useMemo, useState } from 'react';

import { usePermissions } from '@/lib/use-permissions';
import { BlueprintWorkspace } from './components/blueprint-workspace';
import { DomainWorkspace } from './components/domain-workspace';
import { MissionRequestDrawer } from './components/mission-request-drawer';
import { MissionWorkspace } from './components/mission-workspace';
import { useScenarioBlueprints, useScenarioDomains, useScenarioMissions } from './hooks';
import { formatCount, formatWhen, statusColor } from './utils';

export function ScenarioFabricPage() {
  const { can } = usePermissions();
  const domains = useScenarioDomains();
  const blueprints = useScenarioBlueprints();
  const missions = useScenarioMissions();
  const [search, setSearch] = useState('');
  const [missionFilter, setMissionFilter] = useState('ACTIVE');
  const [domainOpen, setDomainOpen] = useState(false);
  const [domainId, setDomainId] = useState<number | null>(null);
  const [blueprintOpen, setBlueprintOpen] = useState(false);
  const [blueprintId, setBlueprintId] = useState<number | null>(null);
  const [requestOpen, setRequestOpen] = useState(false);
  const [requestBlueprintId, setRequestBlueprintId] = useState<number | null>(null);
  const [missionId, setMissionId] = useState<string | null>(null);

  const filteredBlueprints = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return blueprints.data || [];
    return (blueprints.data || []).filter((item) =>
      `${item.name} ${item.domainName} ${item.entityType} ${item.description || ''}`.toLowerCase().includes(query)
    );
  }, [blueprints.data, search]);

  const visibleMissions = useMemo(() => {
    const rows = missions.data || [];
    if (missionFilter === 'ALL') return rows;
    if (missionFilter === 'READY') return rows.filter((item) => item.status.startsWith('READY'));
    return rows.filter((item) => !['READY', 'READY_WITH_WARNINGS', 'FAILED', 'CANCELLED'].includes(item.status));
  }, [missions.data, missionFilter]);

  const request = (id?: number) => {
    setRequestBlueprintId(id || null);
    setRequestOpen(true);
  };

  return (
    <main className="forge-page scenario-page">
      <header className="scenario-page-head">
        <Group gap="sm" wrap="nowrap">
          <span className="scenario-page-mark"><IconSparkles size={21} /></span>
          <div>
            <Text component="h1" fw={900} size="xl">Scenario Fabric</Text>
            <Text size="sm" c="dimmed">Turn a tester's need into covered, governed, ready-to-use data across connected systems.</Text>
          </div>
        </Group>
        <Group gap="xs">
          <Button variant="subtle" leftSection={<IconNetwork size={16} />} onClick={() => setDomainOpen(true)}>Test Domains</Button>
          <Button variant="subtle" leftSection={<IconForms size={16} />} onClick={() => setBlueprintOpen(true)}>Blueprint Studio</Button>
          {can('scenario.run') ? <Button leftSection={<IconPlus size={16} />} onClick={() => request()}>New mission</Button> : null}
        </Group>
      </header>

      <section className="scenario-command-bar">
        <div>
          <Text fw={850} size="lg">What does your test need?</Text>
          <Text size="sm" c="dimmed">Start from an approved business scenario. You choose the variation; the Blueprint protects the data contract.</Text>
        </div>
        <TextInput leftSection={<IconSearch size={15} />} placeholder="Find card, customer, payment, boundary..." value={search} onChange={(event) => setSearch(event.currentTarget.value)} w={410} />
      </section>

      {!domains.isLoading && !domains.data?.length ? (
        <Alert color="blue" title="Publish the first Test Domain">
          Discover a Data Topology, then publish it here. ForgeTDM will pin its system relationships and create a starter Scenario Blueprint.
          <Button variant="light" size="compact-sm" ml="sm" onClick={() => setDomainOpen(true)}>Open Test Domains</Button>
        </Alert>
      ) : null}

      <section className="scenario-library">
        <div className="scenario-section-head">
          <div><Text fw={850}>Approved scenarios</Text><Text size="sm" c="dimmed">Reusable business states, events, outcomes, and coverage rules.</Text></div>
          <Badge variant="light">{filteredBlueprints.length} available</Badge>
        </div>
        {blueprints.isLoading ? (
          <div className="scenario-loading"><Loader size="sm" /> Loading scenario library</div>
        ) : filteredBlueprints.length ? (
          <SimpleGrid cols={{ base: 1, md: 2, xl: 3 }} spacing="md">
            {filteredBlueprints.map((blueprint) => (
              <article className="scenario-blueprint-card" key={blueprint.id}>
                <div className="scenario-blueprint-card-top">
                  <span><IconForms size={18} /></span>
                  <Group gap={6}><Badge variant="light">{blueprint.entityType}</Badge><Badge color="gray" variant="light">v{blueprint.versionNo}</Badge></Group>
                </div>
                <div>
                  <Text fw={850} size="lg" lineClamp={1}>{blueprint.name}</Text>
                  <Text size="xs" c="blue">{blueprint.domainName}</Text>
                  <Text size="sm" c="dimmed" lineClamp={2} mt={6}>{blueprint.description}</Text>
                </div>
                <div className="scenario-techniques">
                  {(Array.isArray(blueprint.coverage.techniques) ? blueprint.coverage.techniques : []).slice(0, 4).map((item) => <span key={String(item)}>{String(item).replaceAll('_', ' ')}</span>)}
                </div>
                <Group justify="space-between">
                  {can('scenario.manage') ? <Button variant="subtle" size="compact-sm" onClick={() => { setBlueprintId(blueprint.id); setBlueprintOpen(true); }}>Edit contract</Button> : <span />}
                  {can('scenario.run') ? <Button size="compact-md" rightSection={<IconArrowRight size={15} />} onClick={() => request(blueprint.id)}>Request data</Button> : null}
                </Group>
              </article>
            ))}
          </SimpleGrid>
        ) : (
          <div className="scenario-empty"><IconForms size={30} /><Text fw={750}>{search ? 'No matching scenarios' : 'No Scenario Blueprints yet'}</Text><Text size="sm" c="dimmed">{search ? 'Try a broader business term.' : 'Publishing a Test Domain creates a starter Blueprint automatically.'}</Text></div>
        )}
      </section>

      <section className="scenario-mission-center">
        <div className="scenario-section-head">
          <div><Text fw={850}>Mission center</Text><Text size="sm" c="dimmed">Your planned, running, and ready-to-test data requests.</Text></div>
          <SegmentedControl size="xs" data={[{ value: 'ACTIVE', label: 'Active' }, { value: 'READY', label: 'Ready' }, { value: 'ALL', label: 'All' }]} value={missionFilter} onChange={setMissionFilter} />
        </div>
        {missions.isLoading ? (
          <div className="scenario-loading"><Loader size="sm" /> Loading Missions</div>
        ) : visibleMissions.length ? (
          <Stack gap={0} className="scenario-mission-list">
            {visibleMissions.map((mission) => (
              <button type="button" key={mission.id} onClick={() => setMissionId(mission.id)}>
                <span className="scenario-mission-icon"><IconClipboardCheck size={18} /></span>
                <div className="scenario-mission-primary"><strong>{mission.title}</strong><small>{mission.blueprintName} / {mission.domainName}</small></div>
                <div><strong>{formatCount(mission.requestedCount)}</strong><small>requested</small></div>
                <div><strong>{Number(mission.coverage.caseCount || 0)}</strong><small>cases</small></div>
                <div><Badge color={statusColor(mission.status)} variant="light">{mission.status.replaceAll('_', ' ')}</Badge><small>{formatWhen(mission.updatedAt)}</small></div>
                <IconArrowRight size={17} />
              </button>
            ))}
          </Stack>
        ) : (
          <div className="scenario-empty compact"><IconClipboardCheck size={27} /><Text fw={750}>No {missionFilter.toLowerCase()} Missions</Text><Text size="sm" c="dimmed">Choose a scenario above to compile the first request.</Text></div>
        )}
      </section>

      <DomainWorkspace opened={domainOpen} domains={domains.data || []} initialId={domainId} onClose={() => { setDomainOpen(false); setDomainId(null); }} />
      <BlueprintWorkspace opened={blueprintOpen} domains={domains.data || []} blueprints={blueprints.data || []} initialId={blueprintId} onClose={() => { setBlueprintOpen(false); setBlueprintId(null); }} />
      <MissionRequestDrawer opened={requestOpen} blueprints={blueprints.data || []} initialBlueprintId={requestBlueprintId} onClose={() => setRequestOpen(false)} onCreated={(mission) => { setRequestOpen(false); setMissionId(mission.id); }} />
      <MissionWorkspace missionId={missionId} onClose={() => setMissionId(null)} />
    </main>
  );
}
