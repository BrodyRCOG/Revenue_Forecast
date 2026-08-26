package com.cognizant.revintel.repository;

import com.cognizant.revintel.repository.projection.DataCatalogRow;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reports row counts per table for the Data Sources tab.
 *
 * <p>Not a Spring Data interface -- it has no entity of its own -- but it lives in the repository
 * layer because counting rows is database access, and services are not allowed to do that
 * directly.
 */
@Repository
public class DataCatalogRepository {

    private final ClientRepository clients;
    private final OemModelRepository oemModels;
    private final InstalledBaseRepository installedBase;
    private final ContractRepository contracts;
    private final OpportunityRepository opportunities;
    private final ProjectBillingRepository projectBilling;
    private final WorkforceRepository workforce;
    private final TargetRepository targets;

    public DataCatalogRepository(ClientRepository clients,
                                 OemModelRepository oemModels,
                                 InstalledBaseRepository installedBase,
                                 ContractRepository contracts,
                                 OpportunityRepository opportunities,
                                 ProjectBillingRepository projectBilling,
                                 WorkforceRepository workforce,
                                 TargetRepository targets) {
        this.clients = clients;
        this.oemModels = oemModels;
        this.installedBase = installedBase;
        this.contracts = contracts;
        this.opportunities = opportunities;
        this.projectBilling = projectBilling;
        this.workforce = workforce;
        this.targets = targets;
    }

    public List<DataCatalogRow> catalog() {
        long realAnchors = oemModels.countByRealAnchorTrue();
        return List.of(
                new DataCatalogRow("clients", "Client Master", "Reference", clients.count(),
                        "synthetic",
                        "Bank and credit-union accounts with segment, asset size, branch count and core platform."),
                new DataCatalogRow("oem_models", "OEM Lifecycle Catalog", "Reference", oemModels.count(),
                        realAnchors + " real anchors + procedural variants",
                        "OEM product models with end-of-sale / end-of-support dates. Real anchors carry a vendor source URL."),
                new DataCatalogRow("installed_base", "Installed Base (CMDB)", "Asset", installedBase.count(),
                        "synthetic",
                        "Deployed assets per client and site, sampled against the lifecycle catalog and weighted by revenue tier."),
                new DataCatalogRow("contracts", "Managed-Services Contracts", "Transactional", contracts.count(),
                        "synthetic",
                        "Recurring ARR with renewal window, renewal-risk grade and NPS."),
                new DataCatalogRow("opportunities", "CRM Opportunities", "Transactional", opportunities.count(),
                        "synthetic",
                        "Open pipeline plus closed Won/Lost history; the history is the win-rate training sample."),
                new DataCatalogRow("project_billing", "Project Billing & Delivery Hours", "Transactional",
                        projectBilling.count(), "synthetic",
                        "Billed hours and amounts per resource group and quarter -- the historical demand baseline."),
                new DataCatalogRow("workforce", "Workforce & Utilisation", "Reference", workforce.count(),
                        "synthetic, sized off historical demand hours",
                        "Headcount, bill rate, target and current utilisation per delivery resource group."),
                new DataCatalogRow("targets", "Quarterly Revenue Targets", "Reference", targets.count(),
                        "synthetic",
                        "Revenue target per practice per quarter; denominator of the pipeline coverage ratio."));
    }

    public long totalRows() {
        return catalog().stream().mapToLong(DataCatalogRow::rowCount).sum();
    }
}
