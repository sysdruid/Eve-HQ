package eve.hq.data.esi

internal class EsiRequestFailed(endpoint: String, status: Int, body: String) :
    Exception("ESI $endpoint answered $status: $body")
