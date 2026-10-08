package dev.badprogrammer.demandsupplymapping.view;

import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Shared offset/limit/sort → Spring Data {@code Pageable} conversion for SupplyDemandView's (both Supply and Demand
 * tabs) lazy grid data providers (added 2026-10-03 alongside
 * {@code SupplyQueryService.page()}/{@code DemandQueryService.page()} — see those classes' Javadoc for why the grids
 * moved off loading every row on every refresh).
 * <p>
 * Vaadin's {@code Query} reports pagination as an offset + limit rather than a page index. Treating
 * {@code offset / limit} as the page number is the standard Vaadin+Spring Data idiom, and it's exact here: Grid's lazy
 * data view always requests pages in multiples of the page size it was configured with (see {@code Grid#setPageSize}),
 * so {@code offset} is always a whole multiple of {@code limit} in practice.
 */
final class GridPagingUtil {

    private GridPagingUtil() {
    }

    /**
     * @param fallbackSort applied when the grid has no active column sort (e.g. right after the view opens), so paging
     *                     is still over a deterministic row order rather than whatever order the database happens to
     *                     return — without this, the same row could appear twice across two pages, or not at all, if
     *                     the unsorted order isn't stable between queries.
     */
    static Pageable toPageable(Query<?, ?> query, Sort fallbackSort) {
        int limit = query.getLimit();
        int page = limit == 0 ? 0 : query.getOffset() / limit;
        return PageRequest.of(page, Math.max(limit, 1), toSort(query.getSortOrders(), fallbackSort));
    }

    private static Sort toSort(List<QuerySortOrder> sortOrders, Sort fallbackSort) {
        if (sortOrders.isEmpty()) {
            return fallbackSort;
        }
        Sort sort = Sort.unsorted();
        for (QuerySortOrder order : sortOrders) {
            Sort.Direction direction = order.getDirection() == SortDirection.DESCENDING ? Sort.Direction.DESC : Sort.Direction.ASC;
            sort = sort.and(Sort.by(direction, order.getSorted()));
        }
        return sort;
    }
}
