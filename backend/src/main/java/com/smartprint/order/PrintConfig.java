package com.smartprint.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "print_configs")
public class PrintConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_file_id", nullable = false, unique = true)
    private Long orderFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "color_mode", nullable = false)
    private PrintColorMode colorMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_mode", nullable = false)
    private PrintSideMode sideMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Orientation orientation;

    @Column(name = "paper_size", nullable = false)
    private String paperSize;

    @Column(name = "page_selection_mode", nullable = false)
    private String pageSelectionMode;

    @Column(name = "page_selection", nullable = false, columnDefinition = "text")
    private String pageSelection;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "selected_page_count")
    private Integer selectedPageCount;

    @Column(nullable = false)
    private Integer copies;

    public enum Orientation { PORTRAIT, LANDSCAPE }

    protected PrintConfig() {}

    public PrintConfig(Long orderId, Long orderFileId, PrintColorMode colorMode,
                       PrintSideMode sideMode, Integer copies, Orientation orientation,
                       String pageSelection, int pageCount, int selectedPageCount) {
        this.orderId = orderId;
        this.orderFileId = orderFileId;
        this.colorMode = colorMode;
        this.sideMode = sideMode;
        this.copies = copies;
        this.orientation = orientation;
        this.paperSize = "A4";
        this.pageSelection = pageSelection;
        this.pageSelectionMode = "ALL".equals(pageSelection) ? "ALL" : "CUSTOM";
        this.pageCount = pageCount;
        this.selectedPageCount = selectedPageCount;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getOrderFileId() { return orderFileId; }
    public PrintColorMode getColorMode() { return colorMode; }
    public PrintSideMode getSideMode() { return sideMode; }
    public Orientation getOrientation() { return orientation; }
    public String getPaperSize() { return paperSize; }
    public String getPageSelectionMode() { return pageSelectionMode; }
    public String getPageSelection() { return pageSelection; }
    public Integer getPageCount() { return pageCount; }
    public Integer getSelectedPageCount() { return selectedPageCount; }
    public Integer getCopies() { return copies; }
}
