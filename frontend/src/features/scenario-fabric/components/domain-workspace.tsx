'use client';

import {
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Textarea
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconArrowRight,
  IconDatabase,
  IconLink,
  IconNetwork,
  IconPlus,
  IconSparkles,
  IconTrash,
  IconX
} from '@tabler/icons-react';
import { useEffect, useMemo, useState } from 'react';

import { useTopologies } from '@/features/topology/hooks';
import { usePermissions } from '@/lib/use-permissions';
import { useScenarioActions, useScenarioDomain, useScenarioProducts } from '../hooks';
import type { DomainSummary } from '../types';
import { errorMessage } from '../utils';

export function DomainWorkspace({
  opened,
  domains,
  initialId,
  onClose
}: {
  opened: boolean;
  domains: DomainSummary[];
  initialId?: number | null;
  onClose: () => void;
}) {
  const { can } = usePermissions();
  const canManage = can('scenario.manage');
  const actions = useScenarioActions();
  const topologies = useTopologies();
  const products = useScenarioProducts();
  const [selectedId, setSelectedId] = useState<number | null>(initialId || domains[0]?.id || null);
  const [creating, setCreating] = useState(false);
  const [productId, setProductId] = useState<string | null>(null);
  const [draft, setDraft] = useState({
    topologyId: null as number | null,
    name: '',
    businessDomain: '',
    description: '',
    visibility: 'GROUP'
  });
  const detail = useScenarioDomain(selectedId);

  useEffect(() => {
    if (opened && initialId) setSelectedId(initialId);
  }, [opened, initialId]);

  const publishable = useMemo(
    () => (topologies.data || []).filter((item) => item.currentVersion > 0 && item.nodeCount > 0),
    [topologies.data]
  );

  const chooseTopology = (value: string | null) => {
    const topology = publishable.find((item) => item.id === Number(value));
    setDraft((current) => ({
      ...current,
      topologyId: topology?.id || null,
      name: topology?.name ? `${topology.name} Test Domain` : '',
      businessDomain: topology?.domain || '',
      description: topology?.description || ''
    }));
  };

  const publish = async () => {
    if (!draft.topologyId) return;
    try {
      const result = await actions.publishDomain.mutateAsync({
        ...draft,
        topologyId: draft.topologyId,
        createStarterBlueprint: true
      });
      setSelectedId(result.summary.id);
      setCreating(false);
      notifications.show({
        color: 'green',
        title: 'Test Domain published',
        message: 'A starter Scenario Blueprint was created from the governed topology.'
      });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not publish Test Domain', message: errorMessage(error) });
    }
  };

  const bind = async () => {
    if (!selectedId || !productId) return;
    try {
      await actions.bindProduct.mutateAsync({ domainId: selectedId, productId });
      setProductId(null);
      notifications.show({ color: 'green', title: 'Delivery product attached', message: 'New missions can now compile into an executable plan.' });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not attach product', message: errorMessage(error) });
    }
  };

  const loadExamples = async () => {
    if (!selectedId) return;
    try {
      const examples = await actions.loadValidationExamples.mutateAsync(selectedId);
      notifications.show({
        color: 'green',
        title: 'Validation scenarios ready',
        message: `${examples.length} reusable banking scenarios are available in the Blueprint library.`
      });
    } catch (error) {
      notifications.show({
        color: 'red',
        title: 'Could not load validation scenarios',
        message: errorMessage(error)
      });
    }
  };

  return (
    <Modal opened={opened} onClose={onClose} fullScreen padding={0} title={null}>
      <div className="scenario-fullscreen">
        <header className="scenario-fullscreen-head">
          <Group gap="sm">
            <span className="scenario-page-mark"><IconNetwork size={20} /></span>
            <div>
              <Text fw={850} size="lg">Test Domains</Text>
              <Text size="sm" c="dimmed">Governed system context that makes scenario requests executable.</Text>
            </div>
          </Group>
          <Group gap="xs">
            {canManage ? <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>Publish topology</Button> : null}
            <Button variant="subtle" color="gray" onClick={onClose} aria-label="Close"><IconX size={20} /></Button>
          </Group>
        </header>

        <div className="scenario-master-detail">
          <aside className="scenario-master-list">
            <Text size="xs" fw={800} c="dimmed" tt="uppercase">Published domains</Text>
            {domains.map((domain) => (
              <button
                type="button"
                className={domain.id === selectedId ? 'is-active' : ''}
                key={domain.id}
                onClick={() => setSelectedId(domain.id)}
              >
                <span><IconDatabase size={16} /></span>
                <div>
                  <strong>{domain.name}</strong>
                  <small>{domain.businessDomain || 'Business domain'} / {domain.blueprintCount} scenario(s)</small>
                </div>
              </button>
            ))}
            {!domains.length ? <Text size="sm" c="dimmed">No Test Domains have been published.</Text> : null}
          </aside>

          <main className="scenario-detail-pane">
            {creating ? (
              <section className="scenario-authoring-panel">
                <div>
                  <Text fw={800} size="lg">Publish a discovered topology</Text>
                  <Text size="sm" c="dimmed">This pins the exact topology version and hash used by every Mission.</Text>
                </div>
                <SimpleGrid cols={{ base: 1, md: 2 }}>
                  <Select
                    label="Discovered topology"
                    searchable
                    data={publishable.map((item) => ({
                      value: String(item.id),
                      label: `${item.name} / ${item.nodeCount} objects / v${item.currentVersion}`
                    }))}
                    value={draft.topologyId ? String(draft.topologyId) : null}
                    onChange={chooseTopology}
                    placeholder="Choose completed topology"
                  />
                  <TextInput label="Test Domain name" minLength={8} maxLength={120} value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.currentTarget.value })} />
                  <TextInput label="Business domain" value={draft.businessDomain} onChange={(event) => setDraft({ ...draft, businessDomain: event.currentTarget.value })} />
                  <Select label="Visibility" data={['PRIVATE', 'GROUP', 'SHARED']} value={draft.visibility} onChange={(value) => setDraft({ ...draft, visibility: value || 'GROUP' })} />
                </SimpleGrid>
                <Textarea label="Purpose" minRows={3} maxLength={2000} value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.currentTarget.value })} />
                <Group justify="flex-end">
                  <Button variant="default" onClick={() => setCreating(false)}>Cancel</Button>
                  <Button loading={actions.publishDomain.isPending} disabled={!draft.topologyId || draft.name.trim().length < 8} onClick={publish}>Publish and create starter Blueprint</Button>
                </Group>
              </section>
            ) : detail.isLoading ? (
              <div className="scenario-loading"><Loader size="sm" /> Loading Test Domain</div>
            ) : detail.data ? (
              <Stack gap="lg">
                <div className="scenario-detail-title">
                  <div>
                    <Group gap="xs"><Text fw={850} size="xl">{detail.data.summary.name}</Text><Badge variant="light">{detail.data.summary.status}</Badge></Group>
                    <Text c="dimmed">{detail.data.summary.description || 'No description recorded.'}</Text>
                  </div>
                  <Group gap="sm">
                    {canManage ? (
                      <Button
                        variant="light"
                        leftSection={<IconSparkles size={16} />}
                        loading={actions.loadValidationExamples.isPending}
                        onClick={loadExamples}
                      >
                        Load validation scenarios
                      </Button>
                    ) : null}
                    <div className="scenario-lineage-chip">Topology v{detail.data.summary.topologyVersion}<small>{detail.data.summary.topologyHash.slice(0, 12)}</small></div>
                  </Group>
                </div>

                <SimpleGrid cols={{ base: 1, md: 3 }}>
                  <Fact label="Systems / sources" value={String(detail.data.settings.sourceCount || 0)} />
                  <Fact label="Business objects" value={String(detail.data.settings.nodeCount || 0)} />
                  <Fact label="Relationships" value={String(detail.data.relationships.length)} />
                </SimpleGrid>

                <section className="scenario-section">
                  <div className="scenario-section-head">
                    <div><Text fw={800}>Execution binding</Text><Text size="sm" c="dimmed">The approved self-service product that physically delivers each Mission.</Text></div>
                  </div>
                  <Group align="flex-end">
                    <Select
                      label="Approved delivery product"
                      searchable
                      data={(products.data || []).map((item) => ({ value: item.id, label: `${item.label} / ${item.productType}` }))}
                      value={productId}
                      onChange={setProductId}
                      placeholder="Choose from Self-Service catalog"
                      style={{ flex: 1 }}
                      disabled={!canManage}
                    />
                    <Button leftSection={<IconLink size={16} />} disabled={!productId || !canManage} loading={actions.bindProduct.isPending} onClick={bind}>Attach</Button>
                  </Group>
                  <div className="scenario-asset-list">
                    {detail.data.assets.map((asset) => (
                      <div key={asset.id}>
                        <span><IconDatabase size={16} /></span>
                        <div><strong>{asset.artifactId}</strong><small>{asset.assetType.replaceAll('_', ' ')} / {asset.assetRole.toLowerCase()}</small></div>
                        {canManage ? <Button variant="subtle" color="red" size="compact-sm" aria-label="Remove binding" onClick={() => actions.unbindProduct.mutate({ domainId: detail.data!.summary.id, assetId: asset.id })}><IconTrash size={15} /></Button> : null}
                      </div>
                    ))}
                    {!detail.data.assets.length ? <Alert color="yellow">No execution product is attached. Blueprints can be designed, but Missions cannot launch.</Alert> : null}
                  </div>
                </section>

                <section className="scenario-section">
                  <div className="scenario-section-head">
                    <div><Text fw={800}>Relationship meaning</Text><Text size="sm" c="dimmed">Business-readable context retained from physical topology evidence.</Text></div>
                    <Badge variant="light">{detail.data.relationships.length} links</Badge>
                  </div>
                  <ScrollArea h={290} type="auto">
                    <div className="scenario-relationship-list">
                      {detail.data.relationships.map((relationship) => (
                        <div key={relationship.edgeId}>
                          <IconArrowRight size={16} />
                          <div><strong>{relationship.statement}</strong><small>{relationship.evidenceType.replaceAll('_', ' ')} / {relationship.decisionStatus.toLowerCase()}</small></div>
                        </div>
                      ))}
                    </div>
                  </ScrollArea>
                </section>
              </Stack>
            ) : (
              <div className="scenario-empty"><IconNetwork size={30} /><Text fw={750}>Choose or publish a Test Domain</Text></div>
            )}
          </main>
        </div>
      </div>
    </Modal>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return <div className="scenario-fact"><span>{label}</span><strong>{value}</strong></div>;
}
