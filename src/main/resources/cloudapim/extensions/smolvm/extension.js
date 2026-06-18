(function () {
  const extensionId = 'cloud-apim.extensions.SmolMachine';

  Otoroshi.registerExtension(extensionId, false, (ctx) => {
    const React = ctx.dependencies.react;
    const Component = React.Component;
    const Table = ctx.dependencies.Components.Inputs.Table;
    const BackOfficeServices = ctx.dependencies.BackOfficeServices;
    const uuid = ctx.dependencies.uuid;

    const GROUP = 'smolvm.extensions.cloud-apim.com';
    const VERSION = 'v1';
    const PLURAL = 'smol-machines';
    const BASE = 'extensions/cloud-apim/smolvm/smol-machines';

    class SmolMachinesPage extends Component {
      formSchema = {
        _loc: { type: 'location', props: {} },
        id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
        name: { type: 'string', props: { label: 'Name', placeholder: 'My smol machine' } },
        description: { type: 'string', props: { label: 'Description' } },
        enabled: { type: 'bool', props: { label: 'Enabled' } },
        tags: { type: 'array', props: { label: 'Tags' } },
        metadata: { type: 'object', props: { label: 'Metadata' } },

        'spec.image': { type: 'string', props: { label: 'Image', placeholder: 'node:22-alpine' } },
        'spec.instances': { type: 'number', props: { label: 'Instances', help: '0 = ephemeral (fresh VM per request, torn down after); n = persistent pool size' } },
        'spec.mode': {
          type: 'select',
          props: {
            label: 'Execution mode',
            possibleValues: [
              { value: 'service', label: 'service (HTTP server in image, proxied)' },
              { value: 'exec', label: 'exec (stdin JSON -> stdout JSON)' },
              { value: 'service-via-exec', label: 'service-via-exec (exec launches the HTTP server)' },
            ],
          },
        },
        'spec.runtime': {
          type: 'select',
          props: {
            label: 'Runtime',
            possibleValues: [
              { value: 'none', label: 'none' },
              { value: 'node', label: 'node (node:22-alpine — run / npm / npx)' },
              { value: 'bun', label: 'bun (oven/bun:latest — run / bun add / bunx)' },
            ],
          },
        },

        'spec.hosts': { type: 'array', props: { label: 'smolvm hosts', placeholder: 'http://host:8080' } },
        'spec.hosts_url': { type: 'string', props: { label: 'Hosts Pool URL', help: 'An URL that returns a json array of smolvm hosts' } },
        'spec.network': { type: 'bool', props: { label: 'Network enabled (outbound)' } },
        'spec.allow_cidrs': { type: 'array', props: { label: 'Egress allowed CIDRs' } },
        'spec.cpus': { type: 'number', props: { label: 'vCPUs' } },
        'spec.memory_mb': { type: 'number', props: { label: 'Memory (MiB)' } },
        'spec.storage_gb': { type: 'number', props: { label: 'Storage (GiB)' } },
        'spec.gpu': { type: 'bool', props: { label: 'GPU' } },

        'spec.service_port': { type: 'number', props: { label: 'Service port (guest)' } },
        'spec.readiness_path': { type: 'string', props: { label: 'Readiness path' } },
        'spec.readiness_timeout': { type: 'number', props: { label: 'Readiness timeout (ms)' } },
        'spec.launch_command': { type: 'array', props: { label: 'Launch command (service-via-exec)', placeholder: 'node' } },

        'spec.exec_command': { type: 'array', props: { label: 'Exec command (exec mode)' } },
        'spec.env': { type: 'object', props: { label: 'Env. variables' } },
        'spec.workdir': { type: 'string', props: { label: 'Working directory' } },

        'spec.code': { type: 'monaco', props: { label: 'Inline code', language: 'javascript', height: 400, help: 'runtime=node/bun: when set, runs `<runtime> <code file>` instead of exec/launch command' } },
        'spec.code_file': { type: 'string', props: { label: 'Code file path', placeholder: '/app/index.js' } },
        'spec.dependencies': { type: 'array', props: { label: 'npm dependencies', placeholder: 'lodash', help: 'npm install-ed once at provisioning before the code runs' } },

        'spec.boot_timeout': { type: 'number', props: { label: 'Boot timeout (ms)' } },
        'spec.request_timeout': { type: 'number', props: { label: 'Request timeout (ms)' } },
        'spec.idle_timeout': { type: 'number', props: { label: 'Idle timeout (ms)', help: 'Idle instances are reaped after this' } },
      };

      columns = [
        { title: 'Name', filterId: 'name', content: (item) => item.name },
        { title: 'Image', filterId: 'spec.image', content: (item) => (item.spec || {}).image },
        { title: 'Mode', filterId: 'spec.mode', content: (item) => (item.spec || {}).mode },
        { title: 'Instances', filterId: 'spec.instances', content: (item) => (item.spec || {}).instances },
      ];

      formFlow = [
        '_loc', 'id', 'name', 'description', 'enabled', 'tags', 'metadata',
        '---',
        'spec.image', 'spec.instances', 'spec.mode', 'spec.runtime',
        '>>>Hosts & network',
        'spec.hosts', 'spec.hosts_url', 'spec.network', 'spec.allow_cidrs',
        '>>>Resources',
        'spec.cpus', 'spec.memory_mb', 'spec.storage_gb', 'spec.gpu',
        '>>>Service / service-via-exec',
        'spec.service_port', 'spec.readiness_path', 'spec.readiness_timeout', 'spec.launch_command',
        '>>>Exec',
        'spec.exec_command', 'spec.env', 'spec.workdir',
        '>>>Inline code (runtime=node/bun)',
        'spec.code', 'spec.code_file', 'spec.dependencies',
        '>>>Timeouts',
        'spec.boot_timeout', 'spec.request_timeout', 'spec.idle_timeout',
      ];

      componentDidMount() {
        this.props.setTitle('SmolVM Machines');
      }

      client = BackOfficeServices.apisClient(GROUP, VERSION, PLURAL);

      render() {
        return React.createElement(Table, {
          parentProps: this.props,
          selfUrl: BASE,
          defaultTitle: 'All SmolVM Machines',
          defaultValue: () => ({
            id: 'smol-machine_' + uuid(),
            name: 'New smol machine',
            description: 'A new smolvm machine',
            enabled: true,
            tags: [],
            metadata: {},
            spec: {
              //image: 'node:22-alpine',
              image: 'traefik/whoami:latest',
              instances: 1,
              // mode: 'service-via-exec',
              mode: 'service',
              // runtime: 'node',
              runtime: 'none',
              network: true,
              hosts: ['http://127.0.0.1:9080'],
              service_port: 80,
              readiness_path: '/',
              readiness_timeout: 30000,
              // launch_command: ['node', '/app/server.js'],
              launch_command: [],
              cpus: 1,
              memory_mb: 128,
              boot_timeout: 60000,
              request_timeout: 30000,
              idle_timeout: 300000,
            },
          }),
          itemName: 'Smol Machine',
          formSchema: this.formSchema,
          formFlow: this.formFlow,
          columns: this.columns,
          stayAfterSave: true,
          fetchItems: (paginationState) => this.client.findAll(),
          updateItem: this.client.update,
          deleteItem: this.client.delete,
          createItem: this.client.create,
          navigateTo: (item) => {
            window.location = `/bo/dashboard/${BASE}/edit/${item.id}`;
          },
          itemUrl: (item) => `/bo/dashboard/${BASE}/edit/${item.id}`,
          showActions: true,
          showLink: true,
          rowNavigation: true,
          extractKey: (item) => item.id,
          export: true,
          kubernetesKind: GROUP + '/SmolMachine',
        }, null);
      }
    }

    return {
      id: extensionId,
      sidebarItems: [
        {
          title: 'SmolVM Machines',
          text: 'Manage smolvm micro-VM machines',
          path: BASE,
          icon: 'microchip',
        },
      ],
      features: [
        {
          title: 'SmolVM Machines',
          description: 'Manage smolvm micro-VM machines (service / exec / node runtime)',
          link: '/' + BASE,
          display: () => true,
          icon: () => 'fa-microchip',
        },
      ],
      searchItems: [
        {
          action: () => {
            window.location.href = `/bo/dashboard/${BASE}`;
          },
          env: React.createElement('span', { className: 'fas fa-microchip' }, null),
          label: 'SmolVM Machines',
          value: 'smolmachines',
        },
      ],
      routes: [
        { path: `/${BASE}/:taction/:titem`, component: (props) => React.createElement(SmolMachinesPage, props, null) },
        { path: `/${BASE}/:taction`, component: (props) => React.createElement(SmolMachinesPage, props, null) },
        { path: `/${BASE}`, component: (props) => React.createElement(SmolMachinesPage, props, null) },
      ],
      workflowNodes: [
        {
          name: 'extensions.com.cloud-apim.smolmachine.call',
          kind: 'extensions.com.cloud-apim.smolmachine.call',
          display_name: 'Smol Machine call',
          description: 'Send a request to a SmolMachine and return its response',
          icon: 'fas fa-microchip',
          sources: ['output'],
          flow: ['ref', 'method', 'path', 'headers', 'query', 'body_json'],
          form_schema: {
            ref: {
              type: 'select',
              label: 'Smol Machine',
              props: {
                optionsFrom: `/bo/api/proxy/apis/${GROUP}/${VERSION}/${PLURAL}`,
                optionsTransformer: { label: 'name', value: 'id' },
              },
            },
            method: { type: 'string', label: 'Method', props: { placeholder: 'GET' } },
            path: { type: 'string', label: 'Path', props: { placeholder: '/' } },
            headers: { type: 'object', label: 'Headers' },
            query: { type: 'object', label: 'Query' },
            body_json: { type: 'any', label: 'Body (json)', props: { height: '150px' } },
          },
        },
        {
          name: 'extensions.com.cloud-apim.smolmachine.run_code',
          kind: 'extensions.com.cloud-apim.smolmachine.run_code',
          display_name: 'Smol Machine run code',
          description: 'Run JS code on a node/bun SmolMachine and return {exitCode, stdout, stderr}',
          icon: 'fas fa-code',
          sources: ['output'],
          flow: ['ref', 'code', 'esm'],
          form_schema: {
            ref: {
              type: 'select',
              label: 'Smol Machine',
              props: {
                optionsFrom: `/bo/api/proxy/apis/${GROUP}/${VERSION}/${PLURAL}`,
                optionsTransformer: { label: 'name', value: 'id' },
              },
            },
            code: { type: 'any', label: 'Code', props: { language: 'javascript', height: 300 } },
            esm: { type: 'bool', label: 'ESM' },
          },
        },
      ],
    };
  });
})();
