package com.gexaenergy.employeedirectory.repository;

import com.gexaenergy.employeedirectory.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
}
