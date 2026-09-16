package com.gbhackathon.AICareerCode.service;

import com.gbhackathon.AICareerCode.model.JobOpportunity;
import com.gbhackathon.AICareerCode.repository.JobOpportunityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private final JobOpportunityRepository jobRepository;

    public DataSeeder(JobOpportunityRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public void run(String... args) {
        if (jobRepository.count() > 0) {
            log.info("Database already contains job data ({} jobs). Skipping seeding.", jobRepository.count());
            return;
        }

        log.info("Seeding initial tech job opportunities (Domestic & Overseas)...");

        List<JobOpportunity> jobs = List.of(
                // 1. Overseas - Singapore (Visa Sponsored)
                createJob(
                        "Senior Backend Engineer (Distributed Systems)",
                        "Grab Financial Group",
                        "https://images.unsplash.com/photo-1542744173-8e7e53415bb0?w=120&auto=format&fit=crop&q=60",
                        "Marina One, Singapore",
                        "Singapore",
                        true,
                        "HYBRID",
                        "SGD 8,500 - 12,000 / month",
                        "Senior (5+ yrs)",
                        4,
                        "Java, Spring Boot, Microservices, Redis, Kafka, MySQL, System Design",
                        "Kubernetes, AWS, Golang, gRPC",
                        true,
                        true,
                        "English (Professional Working Proficiency)",
                        "Build the e-wallet and payment gateway architecture serving more than 30 million users across Southeast Asia.",
                        "At least 4 years with Java/Spring Boot or Go, including high-throughput transaction processing and microservice architecture.",
                        "Full Employment Pass (EP) visa support, flights, and one month of housing allowance in Singapore.",
                        "https://grab.careers",
                        "LinkedIn"
                ),

                // 2. Overseas - Germany (EU Blue Card)
                createJob(
                        "Full Stack Cloud Engineer (Java & React)",
                        "Zalando SE",
                        "https://images.unsplash.com/photo-1572021335469-31706a17aaef?w=120&auto=format&fit=crop&q=60",
                        "Berlin, Germany",
                        "Germany",
                        true,
                        "HYBRID",
                        "EUR 70,000 - 90,000 / year",
                        "Mid to Senior (3-5 yrs)",
                        3,
                        "Java, Spring Boot, React, TypeScript, Docker, AWS",
                        "PostgreSQL, Kubernetes, Next.js, GraphQL",
                        true,
                        true,
                        "English (Fluent) - German is not required",
                        "Develop Europe's largest fashion e-commerce platform, serving over 50 million customers.",
                        "Solid experience with Java Spring Boot and React/TypeScript, plus a Clean Architecture mindset.",
                        "EU Blue Card sponsorship, a EUR 5,000 relocation allowance, and free German language courses.",
                        "https://jobs.zalando.com",
                        "Relocate.me"
                ),

                // 3. Overseas - Japan (Tokyo)
                createJob(
                        "Cloud & Backend Specialist (Global Team)",
                        "Rakuten Group",
                        "https://images.unsplash.com/photo-1516321318423-f06f85e504b3?w=120&auto=format&fit=crop&q=60",
                        "Tokyo, Japan",
                        "Japan",
                        true,
                        "HYBRID",
                        "JPY 7,000,000 - 10,000,000 / year",
                        "Mid-level (3+ yrs)",
                        3,
                        "Java, Spring Boot, MySQL, Docker, Linux, REST API",
                        "Kubernetes, CI/CD, Redis, Elasticsearch, Python",
                        true,
                        true,
                        "English (Official working language at Rakuten) - Japanese is a plus",
                        "Build global fintech and e-commerce infrastructure at Rakuten Crimson House in Tokyo.",
                        "University degree in IT, fluent English, and strong command of the Java ecosystem.",
                        "Japanese Engineer visa support, flights to Tokyo, and initial company housing.",
                        "https://rakuten.careers",
                        "Daijob"
                ),

                // 4. Remote Global (Work from Vietnam)
                createJob(
                        "Senior Platform Engineer (100% Remote Worldwide)",
                        "GitLab / Remote Tech Partners",
                        "https://images.unsplash.com/photo-1522071820081-009f0129c71c?w=120&auto=format&fit=crop&q=60",
                        "Remote Worldwide",
                        "Global",
                        true,
                        "REMOTE",
                        "$5,000 - $7,500 / month ($60k - $90k/yr)",
                        "Senior (4+ yrs)",
                        4,
                        "Docker, Kubernetes, AWS, CI/CD, Terraform, Python, Linux",
                        "Go, Prometheus, Grafana, Java",
                        false,
                        false,
                        "English (Excellent written and verbal communication)",
                        "Run and scale global cloud-native infrastructure, working flexible hours from anywhere.",
                        "Deep experience with Kubernetes, Infrastructure as Code (Terraform), and CI/CD automation.",
                        "USD salary paid directly to Vietnam, plus a $2,000 home office equipment budget.",
                        "https://about.gitlab.com/jobs",
                        "Wellfound"
                ),

                // 5. Overseas - Australia (Sydney)
                createJob(
                        "Software Engineer - Core Services",
                        "Canva",
                        "https://images.unsplash.com/photo-1551836022-d5d88e9218df?w=120&auto=format&fit=crop&q=60",
                        "Sydney, Australia",
                        "Australia",
                        true,
                        "HYBRID",
                        "AUD 120,000 - 150,000 / year",
                        "Mid to Senior (3-6 yrs)",
                        3,
                        "Java, TypeScript, React, AWS, Microservices, System Design",
                        "Redis, DynamoDB, Distributed Caching",
                        true,
                        true,
                        "English (IELTS 6.5+ equivalent for TSS 482 Visa)",
                        "Help build online design tools used by more than 130 million people every month.",
                        "Strong foundation in algorithms, data structures, and high-load distributed system design.",
                        "Temporary Skill Shortage (TSS) subclass 482 visa sponsorship to relocate to Australia with family.",
                        "https://canva.com/careers",
                        "LinkedIn"
                ),

                // 6. Domestic - Vietnam (Ho Chi Minh City)
                createJob(
                        "Lead Backend Engineer (High-throughput Systems)",
                        "VNG Corporation",
                        "https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=120&auto=format&fit=crop&q=60",
                        "Zalo Campus, District 7, Ho Chi Minh City",
                        "Vietnam",
                        false,
                        "HYBRID",
                        "45,000,000 - 75,000,000 VND / month ($1,800 - $3,000)",
                        "Senior (4+ yrs)",
                        4,
                        "Java, Spring Boot, MySQL, Redis, Kafka, Microservices",
                        "Docker, Kubernetes, Elasticsearch, System Design",
                        false,
                        false,
                        "Vietnamese (Native), English (Technical reading and conversation)",
                        "Own the architecture and optimisation of payment and messaging modules for tens of millions of Vietnamese users.",
                        "Scalable system design skills, with a deep grasp of concurrency, transaction isolation, and large-scale query tuning.",
                        "Annual performance bonus of 3-5 months' salary, premium international health insurance, and on-site gym and yoga.",
                        "https://career.vng.com.vn",
                        "VietnamWorks"
                ),

                // 7. Domestic - Vietnam (Hanoi)
                createJob(
                        "Cloud Solutions & DevOps Architect",
                        "FPT Software Global Delivery",
                        "https://images.unsplash.com/photo-1497366216548-37526070297c?w=120&auto=format&fit=crop&q=60",
                        "FPT Tower, Cau Giay, Hanoi",
                        "Vietnam",
                        false,
                        "HYBRID",
                        "35,000,000 - 55,000,000 VND / month",
                        "Mid to Senior (3+ yrs)",
                        3,
                        "AWS, Docker, Kubernetes, CI/CD, Linux, Terraform",
                        "Java, Python, Azure, GCP",
                        false,
                        false,
                        "English (Confident with US and European clients)",
                        "Design and deliver cloud migration architecture for Fortune 500 multinational corporations.",
                        "AWS Solutions Architect Associate/Professional certification is a strong advantage.",
                        "Short and long-term onsite opportunities in the US, Japan, and Europe, with a clear path to Solution Architect.",
                        "https://career.fpt-software.com",
                        "TopCV"
                ),

                // 8. Domestic - Vietnam (Fintech)
                createJob(
                        "Senior Frontend Engineer (React / Next.js)",
                        "MoMo Super App (M_Service)",
                        "https://images.unsplash.com/photo-1556742049-0a67c5574f73?w=120&auto=format&fit=crop&q=60",
                        "District 7, Ho Chi Minh City",
                        "Vietnam",
                        false,
                        "HYBRID",
                        "40,000,000 - 65,000,000 VND / month",
                        "Senior (3+ yrs)",
                        3,
                        "React, TypeScript, Next.js, JavaScript, REST API, Git",
                        "Tailwind CSS, Redux, Performance Optimization, Unit Testing",
                        false,
                        false,
                        "Vietnamese, conversational English",
                        "Build mini-apps and smooth user interface experiences across the MoMo super-app ecosystem.",
                        "Strong React skills, render performance tuning, Web Vitals, and mobile-first UI/UX design.",
                        "Fast-moving engineering culture with an ESOP stock option plan for key staff.",
                        "https://momo.vn/tuyen-dung",
                        "ITviec"
                ),

                // 9. Remote - US Startup
                createJob(
                        "Full Stack Developer (React & Node/Java)",
                        "Silicon Valley Seed Startup",
                        "https://images.unsplash.com/photo-1519389950473-47ba0277781c?w=120&auto=format&fit=crop&q=60",
                        "Remote (US East Coast timezone overlap)",
                        "United States",
                        true,
                        "REMOTE",
                        "$4,000 - $6,000 / month ($48k - $72k/yr)",
                        "Mid-level (2-4 yrs)",
                        2,
                        "React, TypeScript, Java, Spring Boot, MySQL, REST API",
                        "AWS, Docker, Tailwind CSS, Redis",
                        false,
                        false,
                        "English (Fluent speaking & writing)",
                        "Build an AI SaaS product serving the US enterprise B2B market.",
                        "Self-directed, entrepreneurial, comfortable working independently, and fluent in English.",
                        "Stable USD salary, fully remote, plus startup equity.",
                        "https://angel.co",
                        "AngelList"
                )
        );

        jobRepository.saveAll(jobs);
        log.info("Successfully seeded {} diverse tech job opportunities!", jobs.size());
    }

    private JobOpportunity createJob(
            String title, String company, String logo, String location, String country,
            boolean isOverseas, String workType, String salary, String expLevel, int minExp,
            String reqSkills, String prefSkills, boolean visa, boolean reloc, String lang,
            String desc, String reqs, String benefits, String url, String source
    ) {
        JobOpportunity j = new JobOpportunity();
        j.setTitle(title);
        j.setCompany(company);
        j.setCompanyLogo(logo);
        j.setLocation(location);
        j.setCountry(country);
        j.setIsOverseas(isOverseas);
        j.setWorkType(workType);
        j.setSalaryRange(salary);
        j.setExperienceLevel(expLevel);
        j.setMinYearsExp(minExp);
        j.setRequiredSkills(reqSkills);
        j.setPreferredSkills(prefSkills);
        j.setVisaSponsorship(visa);
        j.setRelocationAssistance(reloc);
        j.setLanguageRequirements(lang);
        j.setDescription(desc);
        j.setRequirements(reqs);
        j.setBenefits(benefits);
        j.setApplyUrl(url);
        j.setSource(source);
        j.setPostedAt(LocalDateTime.now().minusDays((long) (Math.random() * 10)));
        return j;
    }
}
