export function trackOwnedServerSockets(server) {
  const sockets = new Set()
  server.on("connection", socket => {
    sockets.add(socket)
    socket.once("close", () => sockets.delete(socket))
  })
  return sockets
}

export async function closeOwnedServer(server, sockets) {
  for (const socket of sockets) socket.destroy()
  if (!server?.listening) return
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
}
