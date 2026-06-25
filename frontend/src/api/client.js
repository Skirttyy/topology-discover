import axios from 'axios';

// In dezvoltare locala, backend-ul ruleaza pe :8080. Pe Proxmox/Docker,
// se seteaza VITE_API_BASE_URL la build time (vezi README -> Deploy).
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

export const WS_BASE_URL = API_BASE_URL;

// ---- Devices ----
export const listDevices = () => api.get('/api/devices').then(r => r.data);
export const getDevice = (id) => api.get(`/api/devices/${id}`).then(r => r.data);
export const createDevice = (payload) => api.post('/api/devices', payload).then(r => r.data);
export const deleteDevice = (id) => api.delete(`/api/devices/${id}`);

// ---- Topology ----
export const getTopology = () => api.get('/api/topology').then(r => r.data);

// ---- Discovery ----
export const scanSubnet = (payload) => api.post('/api/discovery/scan-subnet', payload).then(r => r.data);
export const runDiscovery = (seedDeviceIds) => api.post('/api/discovery/run', seedDeviceIds).then(r => r.data);
export const stopDiscovery = () => api.post('/api/discovery/stop').then(r => r.data);
export const getDiscoveryStatus = () => api.get('/api/discovery/status').then(r => r.data);
export const resetTopology = () => api.delete('/api/devices').then(r => r.data);

export default api;
