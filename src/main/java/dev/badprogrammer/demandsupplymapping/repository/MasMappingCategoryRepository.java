package dev.badprogrammer.demandsupplymapping.repository;

import dev.badprogrammer.demandsupplymapping.entity.MasMappingCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MasMappingCategoryRepository extends JpaRepository<MasMappingCategory, String> {
    List<MasMappingCategory> findByStatus(String status);
}
