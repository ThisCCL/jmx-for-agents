import assert from "node:assert/strict"
import test from "node:test"

import { closeOwnedServer, trackOwnedServerSockets } from "./helpers/owned-server-sockets.mjs"

test("Given an owned keep-alive socket, when local HTTPS cleanup runs, then it destroys the socket before server close", async () => {
  const listeners = new Map()
  const server = {
    listening: true,
    close(callback) {
      this.closed = true
      callback()
    },
    on(event, listener) {
      listeners.set(event, listener)
    },
  }
  const socket = {
    destroy() { this.destroyed = true },
    once(event, listener) { this.onClose = { event, listener } },
  }
  const sockets = trackOwnedServerSockets(server)
  listeners.get("connection")(socket)

  await closeOwnedServer(server, sockets)

  assert.equal(socket.destroyed, true)
  assert.equal(server.closed, true)
})
