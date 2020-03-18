/*
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.resource;

import org.candlepin.subscriptions.db.TallySnapshotRepository;
import org.candlepin.subscriptions.db.model.Granularity;
import org.candlepin.subscriptions.db.model.ServiceLevel;
import org.candlepin.subscriptions.resteasy.PageLinkCreator;
import org.candlepin.subscriptions.security.auth.AdminOnly;
import org.candlepin.subscriptions.tally.filler.ReportFiller;
import org.candlepin.subscriptions.tally.filler.ReportFillerFactory;
import org.candlepin.subscriptions.util.ApplicationClock;
import org.candlepin.subscriptions.utilization.api.model.TallyReport;
import org.candlepin.subscriptions.utilization.api.model.TallyReportMeta;
import org.candlepin.subscriptions.utilization.api.model.TallySnapshot;
import org.candlepin.subscriptions.utilization.api.resources.TallyApi;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

/**
 * Tally API implementation.
 */
@Component
@Profile("api")
public class TallyResource implements TallyApi {

    @Context UriInfo uriInfo;

    private final TallySnapshotRepository repository;
    private final PageLinkCreator pageLinkCreator;
    private final ApplicationClock clock;

    public TallyResource(TallySnapshotRepository repository, PageLinkCreator pageLinkCreator,
        ApplicationClock clock) {
        this.repository = repository;
        this.pageLinkCreator = pageLinkCreator;
        this.clock = clock;
    }

    @SuppressWarnings("linelength")
    @Override
    @AdminOnly
    public TallyReport getTallyReport(String productId, @NotNull String granularity,
        @NotNull OffsetDateTime beginning, @NotNull OffsetDateTime ending, Integer offset,
        @Min(1) Integer limit, String sla) {
        // When limit and offset are not specified, we will fill the report with dummy
        // records from beginning to ending dates. Otherwise we page as usual.
        Pageable pageable = null;
        boolean fill = limit == null && offset == null;
        if (!fill) {
            pageable = ResourceUtils.getPageable(offset, limit);
        }

        String accountNumber = ResourceUtils.getAccountNumber();
        String serviceLevel = getSnapshotQueryValue(sla);
        Granularity granularityValue = Granularity.valueOf(granularity.toUpperCase());
        Page<org.candlepin.subscriptions.db.model.TallySnapshot> snapshotPage = repository
            .findByAccountNumberAndProductIdAndGranularityAndServiceLevelAndSnapshotDateBetweenOrderBySnapshotDate(
            accountNumber,
            productId,
            granularityValue,
            serviceLevel,
            beginning,
            ending,
            pageable
        );

        List<TallySnapshot> snaps = snapshotPage
            .stream()
            .map(org.candlepin.subscriptions.db.model.TallySnapshot::asApiSnapshot)
            .collect(Collectors.toList());

        TallyReport report = new TallyReport();
        report.setData(snaps);
        report.setMeta(new TallyReportMeta());
        report.getMeta().setGranularity(granularityValue.name());
        report.getMeta().setProduct(productId);
        report.getMeta().setServiceLevel(sla == null ? null : serviceLevel);

        // Only set page links if we are paging (not filling).
        if (pageable != null) {
            report.setLinks(pageLinkCreator.getPaginationLinks(uriInfo, snapshotPage));
        }

        // Fill the report gaps if no paging was requested.
        if (fill) {
            ReportFiller reportFiller = ReportFillerFactory.getInstance(clock, granularityValue);
            reportFiller.fillGaps(report, beginning, ending);
        }

        // Set the count last since the report may have gotten filled.
        report.getMeta().setCount(report.getData().size());

        return report;
    }

    private String getSnapshotQueryValue(String sla) {
        // If the sla parameter was not specified, we assume the query param represents ANY.
        if (sla == null) {
            return ServiceLevel.ANY.getValue();
        }

        // If the sla parameter is not one that we support, then throw an exception.
        // If we don't, the query would default to UNSPECIFIED, which would be confusing.
        ServiceLevel sanitized = ServiceLevel.fromString(sla);
        if (!sla.isEmpty() && ServiceLevel.UNSPECIFIED.equals(sanitized)) {
            throw new BadRequestException("Invalid sla parameter specified.");
        }
        return sanitized.getValue();
    }

}
