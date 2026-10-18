// Checks the hub for exactly one pending_notice member with this phone (TASK-053).
// The hub certificate is self-signed; point HUB_URL at a test hub whose certificate the Maestro
// host trusts, or at a plain-http development hub.
const res = http.get(`${HUB_URL}/members?phone=%2B91${PHONE}`, { headers: { Authorization: `Bearer ${HUB_TOKEN}` } })
if (res.status !== 200) throw new Error(`hub answered ${res.status}`)
const members = json(res.body)
if (members.length !== 1) throw new Error(`expected 1 member, got ${members.length}`)
if (members[0].status !== 'pending_notice') throw new Error(`expected pending_notice, got ${members[0].status}`)
output.memberId = members[0].id
