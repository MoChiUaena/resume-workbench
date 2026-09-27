package dev.localresume;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record ResumeDraft(
    @Min(1) @Max(1) int schemaVersion,
    @NotNull @Pattern(regexp="one|two") String sample,
    @NotBlank @Size(max=30) String name,
    @NotBlank @Size(max=70) String headline,
    @NotNull @Size(max=100) String email,
    @NotNull @Size(max=30) String phone,
    @NotNull @Size(max=40) String location,
    boolean swapImages,
    @NotNull @Valid ImageSlot photo,
    @NotNull @Valid ImageSlot logo) {
    public record ImageSlot(
        @Pattern(regexp="[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") String id,
        boolean visible,
        @Min(16) @Max(36) int widthMm,
        @Min(16) @Max(42) int heightMm,
        @NotNull @Pattern(regexp="cover|contain") String fit,
        @Min(0) @Max(3) int quarterTurns,
        @DecimalMin("1.0") @DecimalMax("2.0") double zoom,
        @Min(0) @Max(100) int positionX,
        @Min(0) @Max(100) int positionY) {
        public boolean shown() { return visible && id != null; }
        public String frameStyle() { return "width:" + widthMm + "mm;height:" + heightMm + "mm"; }
        public String imageStyle() {
            return "object-fit:" + fit + ";object-position:" + positionX + "% " + positionY + "%"
                + ";transform:rotate(" + quarterTurns * 90 + "deg) scale(" + zoom + ")";
        }
    }
}
