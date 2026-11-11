package eve.hq.core.production

import eve.hq.core.character.EveCharacter
import eve.hq.core.industry.JobListing
import eve.hq.core.industry.ListJobs

/** The running jobs that make what a project exists to produce, from every character. */
class ListProjectJobs(private val projects: Projects, private val listJobs: ListJobs) {
    suspend operator fun invoke(id: ProjectId, characters: List<EveCharacter>): JobListing =
        forProject(id, listJobs(characters))

    fun cached(id: ProjectId, characters: List<EveCharacter>): JobListing = forProject(id, listJobs.cached(characters))

    private fun forProject(id: ProjectId, listing: JobListing): JobListing {
        val products = projects.get(id).endProducts
        return listing.copy(rows = listing.rows.filter { it.productId in products })
    }
}
