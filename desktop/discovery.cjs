const dgram = require('node:dgram');
const os = require('node:os');

const DISCOVERY_PORT = 47832;
const MESSAGE = Buffer.from('ZIAR/1');

function broadcastAddresses(interfaces = os.networkInterfaces()) {
  const addresses = new Set(['255.255.255.255']);
  for (const network of Object.values(interfaces).flat()) {
    if (!network || network.family !== 'IPv4' || network.internal || !network.netmask) continue;
    const address = network.address.split('.').map(Number);
    const mask = network.netmask.split('.').map(Number);
    addresses.add(address.map((octet, i) => (octet | (~mask[i] & 255)) & 255).join('.'));
  }
  return [...addresses];
}

function startDiscovery() {
  const socket = dgram.createSocket('udp4');
  socket.on('error', error => console.error('ZiAr discovery:', error));
  let timer;
  socket.bind(0, () => {
    socket.setBroadcast(true);
    const announce = () => {
      for (const address of broadcastAddresses()) {
        socket.send(MESSAGE, DISCOVERY_PORT, address, error => {
          if (error) console.error('ZiAr discovery:', error.message);
        });
      }
    };
    announce();
    timer = setInterval(announce, 2000);
  });
  return () => { clearInterval(timer); socket.close(); };
}

module.exports = { startDiscovery, broadcastAddresses, DISCOVERY_PORT, MESSAGE };
