package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageSlice<T> {
    private List<T> data;
    private PageInfo page;
}
