package com.remu.app.data.state

/** Why Owner mode unpaired this phone from a hub (docs/API_CONTRACT.md 7.4 and 7.6). */
enum class HubIdentityProblem {
    /** The pinned certificate is not the one the cloud has for this shop's hub. */
    NOT_YOUR_HUB,

    /** `GET /health` says the hub is claimed by a different cloud shop. */
    OTHER_SHOP,
}
